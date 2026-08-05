package org.wecode.tools.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.wecode.tools.path.WorkspacePaths;
import org.wecode.tools.result.ToolResult;
import org.wecode.tools.spi.Tool;
import org.wecode.tools.spi.ToolContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 在项目根目录内对文本文件做精确字符串替换，或用空 {@code old_string} 创建新文件。
 * <p>
 * 路径经 {@link WorkspacePaths} 强制落在项目根目录内。
 */
public final class EditTool implements Tool {

    public static final String NAME = "Edit";

    /** 可编辑文件大小硬上限（字节），与 Read 对齐。 */
    static final int MAX_FILE_BYTES = 200 * 1024;

    private static final String PARAMETERS_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "path": {
                  "type": "string",
                  "description": "File path relative to the project root (absolute path also accepted)"
                },
                "old_string": {
                  "type": "string",
                  "description": "Exact text to find. Use empty string only when creating a new file."
                },
                "new_string": {
                  "type": "string",
                  "description": "Replacement text (or full content when creating a new file)"
                },
                "replace_all": {
                  "type": "boolean",
                  "description": "If true, replace every match; if false (default), old_string must match exactly once"
                }
              },
              "required": ["path", "old_string", "new_string"]
            }
            """.stripIndent().replaceAll("\\s+", " ").trim();

    private final ObjectMapper objectMapper;

    /**
     * 使用默认 ObjectMapper。
     */
    public EditTool() {
        this(new ObjectMapper());
    }

    /**
     * @param objectMapper 用于解析 arguments JSON
     */
    public EditTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Edit a text file by exact string replacement. "
                + "By default old_string must match exactly once; set replace_all=true to replace all. "
                + "To create a new file, pass empty old_string and the desired new_string. "
                + "Prefer Read first so old_string is an exact unique snippet.";
    }

    @Override
    public String parametersSchema() {
        return PARAMETERS_SCHEMA;
    }

    @Override
    public ToolResult execute(ToolContext context, String toolCallId, String argumentsJson) {
        final EditArgs args;
        final Path file;
        try {
            args = parseArgs(argumentsJson);
            file = WorkspacePaths.resolveInside(context.projectRoot(), args.path());
        } catch (IllegalArgumentException e) {
            return ToolResult.failed(toolCallId, NAME, e.getMessage());
        }

        // 无变化的编辑没有意义
        if (args.oldString().equals(args.newString())) {
            return ToolResult.failed(
                    toolCallId,
                    NAME,
                    "old_string and new_string are identical; nothing to change"
            );
        }

        try {
            // 文件不存在：仅允许空 old_string 创建
            if (!Files.exists(file)) {
                return createNewFile(toolCallId, args, file);
            }
            // 已存在但不是普通文件
            if (!Files.isRegularFile(file)) {
                return ToolResult.failed(toolCallId, NAME, "Not a regular file: " + args.path());
            }
            return replaceInExistingFile(toolCallId, args, file);
        } catch (IOException e) {
            return ToolResult.failed(toolCallId, NAME, "Failed to edit file: " + e.getMessage());
        }
    }

    /**
     * 创建新文件（自动创建父目录）。
     */
    private static ToolResult createNewFile(String toolCallId, EditArgs args, Path file) throws IOException {
        // 已存在文件才走替换；创建必须 old_string 为空
        if (!args.oldString().isEmpty()) {
            return ToolResult.failed(
                    toolCallId,
                    NAME,
                    "File not found: " + args.path()
                            + ". To create a new file, pass empty old_string."
            );
        }
        Path parent = file.getParent();
        // 父目录可能尚未存在
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, args.newString(), StandardCharsets.UTF_8);
        int bytes = args.newString().getBytes(StandardCharsets.UTF_8).length;
        return ToolResult.ok(toolCallId, NAME, "Created " + args.path() + " (" + bytes + " bytes)");
    }

    /**
     * 对已存在文件做精确替换。
     */
    private static ToolResult replaceInExistingFile(String toolCallId, EditArgs args, Path file)
            throws IOException {
        // 已存在文件不允许用空 old_string 整文件覆盖
        if (args.oldString().isEmpty()) {
            return ToolResult.failed(
                    toolCallId,
                    NAME,
                    "old_string is empty but file already exists: " + args.path()
                            + ". Provide a unique snippet to replace (Read the file first)."
            );
        }

        long size = Files.size(file);
        // 过大文件拒绝整文件读改
        if (size > MAX_FILE_BYTES) {
            return ToolResult.failed(
                    toolCallId,
                    NAME,
                    "File too large (" + size + " bytes, limit " + MAX_FILE_BYTES + ")"
            );
        }

        String content = Files.readString(file, StandardCharsets.UTF_8);
        // 二进制粗检：出现 NUL 则拒绝
        if (content.indexOf('\0') >= 0) {
            return ToolResult.failed(toolCallId, NAME, "File appears to be binary, not text: " + args.path());
        }

        int matches = countMatches(content, args.oldString());
        // 未找到
        if (matches == 0) {
            return ToolResult.failed(
                    toolCallId,
                    NAME,
                    "old_string not found in " + args.path()
                            + ". Read the file and copy an exact unique snippet."
            );
        }
        // 默认要求唯一匹配
        if (!args.replaceAll() && matches > 1) {
            return ToolResult.failed(
                    toolCallId,
                    NAME,
                    "old_string matched " + matches + " times in " + args.path()
                            + ". Expand context for a unique match, or set replace_all=true."
            );
        }

        String updated = replaceExact(content, args.oldString(), args.newString(), args.replaceAll());
        Files.writeString(file, updated, StandardCharsets.UTF_8);

        String unit = matches == 1 ? "replacement" : "replacements";
        return ToolResult.ok(toolCallId, NAME, "Edited " + args.path() + " (" + matches + " " + unit + ")");
    }

    /**
     * 统计非重叠精确匹配次数。
     *
     * @param content   全文
     * @param oldString 旧文本（非空）
     * @return 出现次数
     */
    static int countMatches(String content, String oldString) {
        int count = 0;
        int from = 0;
        // 每次从匹配结束后继续，避免重叠重复计数
        while (true) {
            int index = content.indexOf(oldString, from);
            if (index < 0) {
                break;
            }
            count++;
            from = index + oldString.length();
        }
        return count;
    }

    /**
     * 精确子串替换（非正则）。
     *
     * @param content    原文
     * @param oldString  旧文本
     * @param newString  新文本
     * @param replaceAll true 替换全部；false 只替换第一处
     * @return 替换后文本
     */
    static String replaceExact(String content, String oldString, String newString, boolean replaceAll) {
        // 全量：String.replace 本身是字面量替换
        if (replaceAll) {
            return content.replace(oldString, newString);
        }
        int index = content.indexOf(oldString);
        // 调用方已校验至少一处；此处防御
        if (index < 0) {
            return content;
        }
        return content.substring(0, index) + newString + content.substring(index + oldString.length());
    }

    /**
     * 解析模型传入的 JSON 参数。
     */
    private EditArgs parseArgs(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            throw new IllegalArgumentException("argumentsJson is required");
        }
        final JsonNode root;
        try {
            root = objectMapper.readTree(argumentsJson);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid JSON arguments: " + e.getMessage());
        }
        // 必须是对象
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("arguments must be a JSON object");
        }

        JsonNode pathNode = root.get("path");
        // path 必填且非空
        if (pathNode == null || pathNode.isNull() || !pathNode.isTextual() || pathNode.asText().isBlank()) {
            throw new IllegalArgumentException("path is required and must be a non-blank string");
        }

        JsonNode oldNode = root.get("old_string");
        // old_string 必填（允许空串用于创建）
        if (oldNode == null || oldNode.isNull() || !oldNode.isTextual()) {
            throw new IllegalArgumentException("old_string is required and must be a string");
        }

        JsonNode newNode = root.get("new_string");
        // new_string 必填（允许空串）
        if (newNode == null || newNode.isNull() || !newNode.isTextual()) {
            throw new IllegalArgumentException("new_string is required and must be a string");
        }

        boolean replaceAll = false;
        JsonNode replaceAllNode = root.get("replace_all");
        // 可选布尔；缺省 false
        if (replaceAllNode != null && !replaceAllNode.isNull()) {
            if (!replaceAllNode.isBoolean()) {
                throw new IllegalArgumentException("replace_all must be a boolean");
            }
            replaceAll = replaceAllNode.asBoolean();
        }

        return new EditArgs(
                pathNode.asText().trim(),
                oldNode.asText(),
                newNode.asText(),
                replaceAll
        );
    }

    /**
     * Edit 参数。
     *
     * @param path        目标路径
     * @param oldString   旧文本
     * @param newString   新文本
     * @param replaceAll  是否全量替换
     */
    private record EditArgs(String path, String oldString, String newString, boolean replaceAll) {
    }
}
