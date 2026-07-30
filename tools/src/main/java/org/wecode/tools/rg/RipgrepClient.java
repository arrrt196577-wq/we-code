package org.wecode.tools.rg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * ripgrep 进程封装：供 Glob / Grep 共用。
 * <p>
 * 可执行文件解析顺序：{@code WECODE_RG_PATH} → PATH 中的 {@code rg}/{@code rg.exe}。
 */
public final class RipgrepClient {

    /** 环境变量：显式指定 rg 可执行文件路径。 */
    public static final String ENV_RG_PATH = "WECODE_RG_PATH";

    /** 默认超时（秒）。 */
    public static final long DEFAULT_TIMEOUT_SECONDS = 30L;

    /** 默认结果条数上限。 */
    public static final int DEFAULT_LIMIT = 100;

    private final Path rgExecutable;
    private final long timeoutSeconds;
    private final ObjectMapper objectMapper;

    /**
     * @param rgExecutable  rg 可执行文件绝对路径
     * @param timeoutSeconds 进程超时秒数
     * @param objectMapper   解析 --json 输出
     */
    public RipgrepClient(Path rgExecutable, long timeoutSeconds, ObjectMapper objectMapper) {
        Objects.requireNonNull(rgExecutable, "rgExecutable");
        Objects.requireNonNull(objectMapper, "objectMapper");
        if (timeoutSeconds < 1) {
            throw new IllegalArgumentException("timeoutSeconds must be >= 1");
        }
        this.rgExecutable = rgExecutable.toAbsolutePath().normalize();
        this.timeoutSeconds = timeoutSeconds;
        this.objectMapper = objectMapper;
    }

    /**
     * 从环境解析 rg，使用默认超时与 ObjectMapper。
     */
    public static RipgrepClient fromEnvironment() {
        return new RipgrepClient(resolveExecutable(), DEFAULT_TIMEOUT_SECONDS, new ObjectMapper());
    }

    /**
     * 解析 rg 可执行文件路径。
     *
     * @return 绝对路径
     */
    public static Path resolveExecutable() {
        String configured = System.getenv(ENV_RG_PATH);
        // 优先使用显式配置
        if (configured != null && !configured.isBlank()) {
            Path path = Path.of(configured.trim()).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path) || !Files.isExecutable(path)) {
                // Windows 上 isExecutable 可能不可靠，存在即接受
                if (!Files.isRegularFile(path)) {
                    throw new RipgrepException(
                            ENV_RG_PATH + " is set but not a file: " + path
                                    + ". Install ripgrep: https://github.com/BurntSushi/ripgrep"
                    );
                }
            }
            return path;
        }

        Path found = findOnPath();
        // PATH 中未找到
        if (found == null) {
            throw new RipgrepException(
                    "ripgrep (rg) not found on PATH. Set " + ENV_RG_PATH
                            + " or install: https://github.com/BurntSushi/ripgrep"
            );
        }
        return found;
    }

    /**
     * 按路径模式列出文件（类似 {@code rg --files -g pattern}）。
     *
     * @param cwd     搜索根目录
     * @param pattern glob 模式，如 {@code **}/{@code *.java}
     * @param limit   最多返回条数
     * @return 相对路径列表与是否截断
     */
    public FilesResult files(Path cwd, String pattern, int limit) {
        Objects.requireNonNull(cwd, "cwd");
        Objects.requireNonNull(pattern, "pattern");
        if (pattern.isBlank()) {
            throw new RipgrepException("glob pattern must not be blank");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1");
        }

        List<String> args = new ArrayList<>();
        args.add("--no-config");
        args.add("--files");
        args.add("--glob=" + pattern);
        args.add("--glob=!**/.git/**");
        args.add(".");

        RawRun run = run(cwd, args, limit, line -> {
            String normalized = normalizeRelativePath(line);
            // 空行丢弃
            if (normalized.isEmpty()) {
                return null;
            }
            return normalized;
        });
        return new FilesResult(run.items().stream().map(String.class::cast).toList(), run.truncated());
    }

    /**
     * 按正则搜索文件内容（{@code rg --json}）。
     *
     * @param cwd     搜索根目录
     * @param pattern 内容正则
     * @param include 可选文件 glob（如 {@code *.java}），可为 null
     * @param file    可选：只搜该相对路径文件；为 null 时搜整个 cwd（传 {@code .}）
     * @param limit   最多返回命中条数
     * @return 命中列表与是否截断
     */
    public SearchResult search(Path cwd, String pattern, String include, String file, int limit) {
        Objects.requireNonNull(cwd, "cwd");
        Objects.requireNonNull(pattern, "pattern");
        if (pattern.isBlank()) {
            throw new RipgrepException("search pattern must not be blank");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1");
        }

        List<String> args = new ArrayList<>();
        args.add("--no-config");
        args.add("--json");
        args.add("--hidden");
        args.add("--no-messages");
        // 可选 include glob
        if (include != null && !include.isBlank()) {
            args.add("--glob=" + include.trim());
        }
        args.add("--glob=!**/.git/**");
        args.add("--");
        args.add(pattern);
        // 目标：单文件或当前目录
        if (file != null && !file.isBlank()) {
            args.add(file.trim().replace('\\', '/'));
        } else {
            args.add(".");
        }

        RawRun run = run(cwd, args, limit, line -> parseMatchLine(line));
        List<RipgrepMatchHit> hits = new ArrayList<>();
        for (Object item : run.items()) {
            hits.add((RipgrepMatchHit) item);
        }
        return new SearchResult(List.copyOf(hits), run.truncated());
    }

    /**
     * 便捷重载：目录级搜索，无单文件限制。
     */
    public SearchResult search(Path cwd, String pattern, String include, int limit) {
        return search(cwd, pattern, include, null, limit);
    }

    /**
     * 解析 --json 输出中的 match 行；非 match 或坏 JSON 返回 null（跳过）。
     */
    private RipgrepMatchHit parseMatchLine(String line) {
        final JsonNode root;
        try {
            root = objectMapper.readTree(line);
        } catch (IOException e) {
            return null;
        }
        // 只关心 match 事件
        if (root == null || !"match".equals(root.path("type").asText())) {
            return null;
        }
        JsonNode data = root.path("data");
        String path = data.path("path").path("text").asText(null);
        int lineNo = data.path("line_number").asInt(-1);
        String text = data.path("lines").path("text").asText("");
        // 必要字段缺失则跳过
        if (path == null || path.isBlank() || lineNo < 1) {
            return null;
        }
        // 去掉行尾换行，避免 observation 多空行
        if (text.endsWith("\r\n")) {
            text = text.substring(0, text.length() - 2);
        } else if (text.endsWith("\n") || text.endsWith("\r")) {
            text = text.substring(0, text.length() - 1);
        }
        return new RipgrepMatchHit(normalizeRelativePath(path), lineNo, text);
    }

    /**
     * 启动 rg，按行解析，最多收集 limit+1 条以判断截断。
     */
    private RawRun run(Path cwd, List<String> args, int limit, LineParser parser) {
        if (!Files.isDirectory(cwd)) {
            throw new RipgrepException("cwd is not a directory: " + cwd);
        }

        List<String> command = new ArrayList<>(args.size() + 1);
        command.add(rgExecutable.toString());
        command.addAll(args);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(cwd.toFile());
        pb.redirectErrorStream(false);

        final Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new RipgrepException("failed to start ripgrep: " + e.getMessage(), e);
        }

        List<Object> items = new ArrayList<>();
        StringBuilder stderr = new StringBuilder();
        Thread stderrDrain = Thread.ofVirtual().start(() -> drain(process.getErrorStream(), stderr, 8 * 1024));

        boolean truncated = false;
        try {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)
            )) {
                String line;
                // 多读一条用于 truncated 判断
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        continue;
                    }
                    Object parsed = parser.parse(line);
                    // 解析器返回 null 表示跳过该行
                    if (parsed == null) {
                        continue;
                    }
                    items.add(parsed);
                    // 已超过 limit：标记截断并结束读取，避免撑爆管道
                    if (items.size() > limit) {
                        truncated = true;
                        break;
                    }
                }
            }
            // 截断后强杀，防止 rg 继续写 stdout 阻塞
            if (truncated) {
                process.destroyForcibly();
            }

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            // 超时则强杀
            if (!finished) {
                process.destroyForcibly();
                throw new RipgrepException("ripgrep timed out after " + timeoutSeconds + "s");
            }
            stderrDrain.join(2_000);

            int code = process.exitValue();
            String err = stderr.toString().trim();
            List<Object> limited = truncated ? List.copyOf(items.subList(0, limit)) : List.copyOf(items);

            // 截断场景下 exit code 可能因 destroy 非 0，仍返回已收集结果
            if (truncated) {
                return new RawRun(limited, true);
            }

            // 0 = 有匹配；1 = 无匹配；2 = 错误（可能部分结果）
            if (code == 0 || code == 1) {
                // code==1 时 rg 表示无匹配，items 应为空
                if (code == 1 && limited.isEmpty()) {
                    return new RawRun(List.of(), false);
                }
                return new RawRun(limited, false);
            }
            // 坏正则等
            if (code == 2 && isInvalidPattern(err)) {
                throw new RipgrepException("invalid ripgrep pattern: " + err);
            }
            if (code == 2 && !limited.isEmpty()) {
                // 有部分结果时仍返回（与 OpenCode partial 行为接近）
                return new RawRun(limited, false);
            }
            throw new RipgrepException(
                    err.isEmpty() ? "ripgrep failed with exit code " + code : err
            );
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new RipgrepException("ripgrep interrupted", e);
        } catch (IOException e) {
            process.destroyForcibly();
            throw new RipgrepException("failed to read ripgrep output: " + e.getMessage(), e);
        }
    }

    private static boolean isInvalidPattern(String stderr) {
        String lower = stderr.toLowerCase(Locale.ROOT);
        return lower.contains("regex parse error") || lower.contains("error parsing regex");
    }

    private static void drain(InputStream in, StringBuilder out, int maxBytes) {
        try (InputStream stream = in) {
            byte[] buf = new byte[1024];
            int total = 0;
            int n;
            while ((n = stream.read(buf)) >= 0) {
                int allowed = Math.min(n, maxBytes - total);
                // 已达上限则继续读完但不追加，避免阻塞子进程
                if (allowed > 0) {
                    out.append(new String(buf, 0, allowed, StandardCharsets.UTF_8));
                    total += allowed;
                }
                if (total >= maxBytes) {
                    // 排空剩余
                    while (stream.read(buf) >= 0) {
                        // no-op
                    }
                    break;
                }
            }
        } catch (IOException ignored) {
            // stderr 排空失败不影响主流程
        }
    }

    /**
     * 在 PATH 中查找 rg。
     */
    private static Path findOnPath() {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isBlank()) {
            return null;
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String[] names = windows ? new String[]{"rg.exe", "rg"} : new String[]{"rg"};
        for (String dir : pathEnv.split(windows ? ";" : ":")) {
            if (dir.isBlank()) {
                continue;
            }
            Path dirPath = Path.of(dir.trim());
            for (String name : names) {
                Path candidate = dirPath.resolve(name);
                // 找到普通文件即返回
                if (Files.isRegularFile(candidate)) {
                    return candidate.toAbsolutePath().normalize();
                }
            }
        }
        return null;
    }

    /**
     * 规范化相对路径展示：去前导 ./、统一正斜杠。
     */
    static String normalizeRelativePath(String raw) {
        String s = raw.trim().replace('\\', '/');
        while (s.startsWith("./")) {
            s = s.substring(2);
        }
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        return s;
    }

    @FunctionalInterface
    private interface LineParser {
        Object parse(String line) throws IOException;
    }

    private record RawRun(List<Object> items, boolean truncated) {
    }

    /**
     * files() 结果。
     *
     * @param paths     相对路径列表
     * @param truncated 是否因 limit 截断
     */
    public record FilesResult(List<String> paths, boolean truncated) {
        public FilesResult {
            Objects.requireNonNull(paths, "paths");
            paths = List.copyOf(paths);
        }
    }

    /**
     * search() 结果。
     *
     * @param matches   命中列表
     * @param truncated 是否因 limit 截断
     */
    public record SearchResult(List<RipgrepMatchHit> matches, boolean truncated) {
        public SearchResult {
            Objects.requireNonNull(matches, "matches");
            matches = List.copyOf(matches);
        }
    }
}
