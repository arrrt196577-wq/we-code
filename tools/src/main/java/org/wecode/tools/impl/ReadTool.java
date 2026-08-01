package org.wecode.tools.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.wecode.tools.path.WorkspacePaths;
import org.wecode.tools.result.ToolResult;
import org.wecode.tools.spi.Tool;
import org.wecode.tools.spi.ToolContext;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 在 workspace 内读取文本文件，按行号格式返回给模型。
 * <p>
 * 路径经 {@link WorkspacePaths} 强制落在 workspace 内。
 */
public final class ReadTool implements Tool {

    public static final String NAME = "Read";

    /** 未分段读取时的文件大小硬上限（字节）。 */
    static final int MAX_FILE_BYTES = 200 * 1024;

    /** 未指定 limit 时，最多返回的行数，避免一次灌爆上下文。 */
    static final int DEFAULT_MAX_LINES = 2000;

    private static final String PARAMETERS_JSON = """
            {
              "type": "object",
              "properties": {
                "path": {
                  "type": "string",
                  "description": "File path relative to workspace (absolute path also accepted)"
                },
                "offset": {
                  "type": "integer",
                  "description": "1-based start line (optional)"
                },
                "limit": {
                  "type": "integer",
                  "description": "Max number of lines to return (optional)"
                }
              },
              "required": ["path"]
            }
            """.stripIndent().replaceAll("\\s+", " ").trim();

    private final ObjectMapper objectMapper;

    /**
     * 使用默认 ObjectMapper。
     */
    public ReadTool() {
        this(new ObjectMapper());
    }

    /**
     * @param objectMapper 用于解析 arguments JSON
     */
    public ReadTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Read a text file from the workspace. "
                + "Use offset/limit for large files. Paths are relative to the workspace root.";
    }

    @Override
    public String parametersSchema() {
        return PARAMETERS_JSON;
    }

    @Override
    public ToolResult execute(ToolContext context, String toolCallId, String argumentsJson) {
        // 解析参数与路径沙箱；任何参数/越界问题都以 failed observation 返回
        final ReadArgs args;
        final Path file;
        try {
            args = parseArgs(argumentsJson);
            file = WorkspacePaths.resolveInside(context.workspaceRoot(), args.path());
        } catch (IllegalArgumentException e) {
            return ToolResult.failed(toolCallId, NAME, e.getMessage());
        }

        // 存在性与类型检查
        if (!Files.exists(file)) {
            return ToolResult.failed(toolCallId, NAME, "File not found: " + args.path());
        }
        if (!Files.isRegularFile(file)) {
            return ToolResult.failed(toolCallId, NAME, "Not a regular file: " + args.path());
        }

        try {
            long size = Files.size(file);
            boolean windowed = args.offset() != null || args.limit() != null;
            // 整文件读取时强制大小上限；分段读则按行窗口返回
            if (!windowed && size > MAX_FILE_BYTES) {
                return ToolResult.failed(
                        toolCallId,
                        NAME,
                        "File too large (" + size + " bytes, limit " + MAX_FILE_BYTES
                                + "). Use offset/limit to read a window."
                );
            }

            ReadOutcome outcome = readWindow(file, args, size);
            // 二进制文件拒绝读取
            if (outcome.isBinary()) {
                return ToolResult.failed(toolCallId, NAME, "File appears to be binary, not text: " + args.path());
            }
            return ToolResult.ok(toolCallId, NAME, outcome.content());
        } catch (IOException e) {
            return ToolResult.failed(toolCallId, NAME, "Failed to read file: " + e.getMessage());
        }
    }

    /**
     * 解析模型传入的 JSON 参数。
     */
    private ReadArgs parseArgs(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            throw new IllegalArgumentException("argumentsJson is required");
        }
        final JsonNode root;
        try {
            root = objectMapper.readTree(argumentsJson);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid JSON arguments: " + e.getMessage());
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("arguments must be a JSON object");
        }

        JsonNode pathNode = root.get("path");
        // path 必填且非空
        if (pathNode == null || pathNode.isNull() || !pathNode.isTextual() || pathNode.asText().isBlank()) {
            throw new IllegalArgumentException("path is required and must be a non-blank string");
        }

        Integer offset = readPositiveInt(root, "offset");
        Integer limit = readPositiveInt(root, "limit");
        return new ReadArgs(pathNode.asText().trim(), offset, limit);
    }

    /**
     * 读取可选的正整数参数；缺省返回 null。
     */
    private static Integer readPositiveInt(JsonNode root, String field) {
        JsonNode node = root.get(field);
        // 未提供该字段
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber()) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        int value = node.asInt();
        if (value < 1) {
            throw new IllegalArgumentException(field + " must be >= 1");
        }
        return value;
    }

    /**
     * 按 offset/limit 读取行窗口，并格式化为带行号文本。
     */
    private static ReadOutcome readWindow(Path file, ReadArgs args, long size) throws IOException {
        int startLine = args.offset() == null ? 1 : args.offset();
        int maxLines = args.limit() == null ? DEFAULT_MAX_LINES : args.limit();

        List<String> selected = new ArrayList<>();
        int totalLines = 0;
        boolean truncatedByLimit = false;

        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            // 逐行扫描：统计总行数，并收集窗口内的行
            while ((line = reader.readLine()) != null) {
                totalLines++;
                // 二进制粗检：出现 NUL 则拒绝
                if (line.indexOf('\0') >= 0) {
                    return ReadOutcome.binaryFile();
                }
                if (totalLines < startLine) {
                    continue;
                }
                if (selected.size() < maxLines) {
                    selected.add(line);
                    continue;
                }
                truncatedByLimit = true;
                // 已集满窗口：未指定分段时继续扫完以得到总行数后结束；指定分段则继续计数
                if (args.offset() == null && args.limit() == null) {
                    while ((line = reader.readLine()) != null) {
                        totalLines++;
                        if (line.indexOf('\0') >= 0) {
                            return ReadOutcome.binaryFile();
                        }
                    }
                    break;
                }
            }
        }

        // 空文件
        if (totalLines == 0) {
            return ReadOutcome.text("(empty file)");
        }
        // 起始行超出文件末尾
        if (startLine > totalLines) {
            return ReadOutcome.text(
                    "offset " + startLine + " is beyond end of file (" + totalLines + " lines)"
            );
        }

        int endLine = startLine + selected.size() - 1;
        int width = Math.max(4, String.valueOf(totalLines).length());
        StringBuilder out = new StringBuilder();
        // 分段读取或发生截断时，在头部注明窗口范围
        if (args.offset() != null || args.limit() != null || truncatedByLimit || size > MAX_FILE_BYTES) {
            out.append("lines ").append(startLine).append('-').append(endLine)
                    .append(" of ").append(totalLines).append('\n');
        }
        for (int i = 0; i < selected.size(); i++) {
            int lineNo = startLine + i;
            out.append(String.format("%" + width + "d|%s%n", lineNo, selected.get(i)));
        }
        // 因 limit / 默认行数截断时提示模型
        if (truncatedByLimit) {
            out.append("... truncated, use offset/limit to continue ...\n");
        }
        return ReadOutcome.text(out.toString());
    }

    /**
     * read 工具参数。
     *
     * @param path   文件路径
     * @param offset 起始行（1-based），可为 null
     * @param limit  最大行数，可为 null
     */
    private record ReadArgs(String path, Integer offset, Integer limit) {
    }

    /**
     * 读文件结果：文本内容或判定为二进制。
     *
     * @param content  格式化后的文本
     * @param isBinary true 表示判定为二进制，不应交给模型
     */
    private record ReadOutcome(String content, boolean isBinary) {

        static ReadOutcome text(String content) {
            return new ReadOutcome(content, false);
        }

        static ReadOutcome binaryFile() {
            return new ReadOutcome("", true);
        }
    }
}
