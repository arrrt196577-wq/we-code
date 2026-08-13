package org.wecode.tools.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.wecode.tools.path.WorkspacePaths;
import org.wecode.tools.result.ToolResult;
import org.wecode.tools.rg.RipgrepClient;
import org.wecode.tools.rg.RipgrepException;
import org.wecode.tools.rg.RipgrepMatchHit;
import org.wecode.tools.spi.Tool;
import org.wecode.tools.spi.ToolContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * 在工作区路径中按正则搜索文件内容（基于 ripgrep --json）。
 * <p>
 * 路径经 {@link WorkspacePaths} 强制落在工作区路径内。
 */
public final class GrepTool implements Tool {

    public static final String NAME = "Grep";

    /** 返回命中条数上限。 */
    static final int RESULT_LIMIT = RipgrepClient.DEFAULT_LIMIT;

    private static final String PARAMETERS_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "pattern": {
                  "type": "string",
                  "description": "Regex pattern to search for in file contents"
                },
                "path": {
                  "type": "string",
                  "description": "File or directory to search in, relative to the workspace path. Defaults to the workspace path."
                },
                "include": {
                  "type": "string",
                  "description": "Optional glob to filter files, e.g. *.java or *.{ts,tsx}"
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
    public GrepTool(RipgrepClient ripgrep) {
        this(ripgrep, new ObjectMapper());
    }

    /**
     * @param ripgrep      共用的 ripgrep 客户端
     * @param objectMapper 解析 arguments JSON
     */
    public GrepTool(RipgrepClient ripgrep, ObjectMapper objectMapper) {
        this.ripgrep = Objects.requireNonNull(ripgrep, "ripgrep");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Search file contents in the project with a regex (powered by ripgrep). "
                + "Optional include glob filters by file name. Returns path + line matches.";
    }

    @Override
    public String parametersSchema() {
        return PARAMETERS_SCHEMA;
    }

    @Override
    public ToolResult execute(ToolContext context, String toolCallId, String argumentsJson) {
        final GrepArgs args;
        final Path target;
        try {
            args = parseArgs(argumentsJson);
            target = WorkspacePaths.resolveInside(context.workspacePath(), args.path());
        } catch (IllegalArgumentException e) {
            return ToolResult.failed(toolCallId, NAME, e.getMessage());
        }

        // 目标不存在
        if (!Files.exists(target)) {
            return ToolResult.failed(toolCallId, NAME, "path not found: " + args.path());
        }

        final Path cwd;
        final String fileArg;
        // 单文件：cwd 为父目录，file 为文件名；目录：cwd 即目录，搜 .
        if (Files.isRegularFile(target)) {
            cwd = target.getParent() != null ? target.getParent() : target;
            fileArg = target.getFileName().toString();
        } else if (Files.isDirectory(target)) {
            cwd = target;
            fileArg = null;
        } else {
            return ToolResult.failed(toolCallId, NAME, "path is neither file nor directory: " + args.path());
        }

        try {
            RipgrepClient.SearchResult result = ripgrep.search(
                    cwd,
                    args.pattern(),
                    args.include(),
                    fileArg,
                    RESULT_LIMIT
            );
            return ToolResult.ok(toolCallId, NAME, formatMatches(result.matches(), result.truncated()));
        } catch (RipgrepException e) {
            return ToolResult.failed(toolCallId, NAME, e.getMessage());
        }
    }

    /**
     * 按文件分组排版命中结果。
     */
    static String formatMatches(List<RipgrepMatchHit> matches, boolean truncated) {
        // 无命中
        if (matches.isEmpty()) {
            return "No matches found";
        }
        StringBuilder out = new StringBuilder();
        out.append("Found ").append(matches.size()).append(" matches");
        if (truncated) {
            out.append(" (more matches available)");
        }
        out.append('\n');

        String current = null;
        for (RipgrepMatchHit hit : matches) {
            // 新文件时输出路径头
            if (!hit.path().equals(current)) {
                if (current != null) {
                    out.append('\n');
                }
                current = hit.path();
                out.append(current).append(":\n");
            }
            out.append("  Line ").append(hit.line()).append(": ").append(hit.text()).append('\n');
        }
        if (truncated) {
            out.append('\n')
                    .append("(Results truncated. Consider using a more specific path or pattern.)\n");
        }
        return out.toString();
    }

    private GrepArgs parseArgs(String argumentsJson) {
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

        String include = null;
        JsonNode includeNode = root.get("include");
        // include 可选
        if (includeNode != null && !includeNode.isNull()) {
            if (!includeNode.isTextual() || includeNode.asText().isBlank()) {
                throw new IllegalArgumentException("include must be a non-blank string when provided");
            }
            include = includeNode.asText().trim();
        }
        return new GrepArgs(patternNode.asText().trim(), path, include);
    }

    private record GrepArgs(String pattern, String path, String include) {
    }
}
