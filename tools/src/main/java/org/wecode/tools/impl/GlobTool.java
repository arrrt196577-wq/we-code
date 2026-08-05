package org.wecode.tools.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.wecode.tools.path.WorkspacePaths;
import org.wecode.tools.result.ToolResult;
import org.wecode.tools.rg.RipgrepClient;
import org.wecode.tools.rg.RipgrepException;
import org.wecode.tools.spi.Tool;
import org.wecode.tools.spi.ToolContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 按 glob 模式在项目根目录中列文件（基于 ripgrep --files）。
 * <p>
 * 路径经 {@link WorkspacePaths} 强制落在项目根目录内。
 */
public final class GlobTool implements Tool {

    public static final String NAME = "Glob";

    /** 返回路径条数上限。 */
    static final int RESULT_LIMIT = RipgrepClient.DEFAULT_LIMIT;

    private static final String PARAMETERS_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "pattern": {
                  "type": "string",
                  "description": "Glob pattern to match files, e.g. **/*.java"
                },
                "path": {
                  "type": "string",
                  "description": "Directory to search in, relative to the project root. Defaults to the project root."
                }
              },
              "required": ["pattern"]
            }
            """.stripIndent().replaceAll("\\s+", " ").trim();

    private final RipgrepClient ripgrep;
    private final ObjectMapper objectMapper;

    /**
     * @param ripgrep 共用的 ripgrep 客户端
     */
    public GlobTool(RipgrepClient ripgrep) {
        this(ripgrep, new ObjectMapper());
    }

    /**
     * @param ripgrep      共用的 ripgrep 客户端
     * @param objectMapper 解析 arguments JSON
     */
    public GlobTool(RipgrepClient ripgrep, ObjectMapper objectMapper) {
        this.ripgrep = Objects.requireNonNull(ripgrep, "ripgrep");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Find files by glob pattern in the project (e.g. **/*.java). "
                + "Returns matching file paths. Prefer this over shell find.";
    }

    @Override
    public String parametersSchema() {
        return PARAMETERS_SCHEMA;
    }

    @Override
    public ToolResult execute(ToolContext context, String toolCallId, String argumentsJson) {
        final GlobArgs args;
        final Path searchRoot;
        try {
            args = parseArgs(argumentsJson);
            searchRoot = WorkspacePaths.resolveInside(context.projectRoot(), args.path());
        } catch (IllegalArgumentException e) {
            return ToolResult.failed(toolCallId, NAME, e.getMessage());
        }

        // 搜索根必须是目录
        if (!Files.isDirectory(searchRoot)) {
            return ToolResult.failed(toolCallId, NAME, "glob path must be a directory: " + args.path());
        }

        try {
            RipgrepClient.FilesResult result = ripgrep.files(searchRoot, args.pattern(), RESULT_LIMIT);
            // 无命中
            if (result.paths().isEmpty()) {
                return ToolResult.ok(toolCallId, NAME, "No files found");
            }
            StringBuilder out = new StringBuilder();
            for (String path : result.paths()) {
                out.append(path).append('\n');
            }
            // 截断提示
            if (result.truncated()) {
                out.append('\n')
                        .append("(Results are truncated: showing first ")
                        .append(RESULT_LIMIT)
                        .append(" results. Consider using a more specific path or pattern.)\n");
            }
            return ToolResult.ok(toolCallId, NAME, out.toString());
        } catch (RipgrepException e) {
            return ToolResult.failed(toolCallId, NAME, e.getMessage());
        }
    }

    private GlobArgs parseArgs(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            throw new IllegalArgumentException("argumentsJson is required");
        }
        final JsonNode root;
        try {
            root = objectMapper.readTree(argumentsJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JSON arguments: " + e.getMessage());
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("arguments must be a JSON object");
        }
        JsonNode patternNode = root.get("pattern");
        // pattern 必填
        if (patternNode == null || !patternNode.isTextual() || patternNode.asText().isBlank()) {
            throw new IllegalArgumentException("pattern is required and must be a non-blank string");
        }
        String path = ".";
        JsonNode pathNode = root.get("path");
        // path 可选
        if (pathNode != null && !pathNode.isNull()) {
            if (!pathNode.isTextual() || pathNode.asText().isBlank()) {
                throw new IllegalArgumentException("path must be a non-blank string when provided");
            }
            path = pathNode.asText().trim();
        }
        return new GlobArgs(patternNode.asText().trim(), path);
    }

    private record GlobArgs(String pattern, String path) {
    }
}
