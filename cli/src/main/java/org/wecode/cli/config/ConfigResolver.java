package org.wecode.cli.config;

/**
 * 合并配置优先级：命令行 > 环境变量 > yml > 代码默认值。
 * <p>
 * 密钥建议只放环境变量 {@code WECODE_API_KEY}，不要提交进 Git。
 */
public final class ConfigResolver {

    private ConfigResolver() {
    }

    /**
     * 解析最终生效的 LLM 配置。
     *
     * @param fromFile     yml 中的配置（可为 defaults）
     * @param cliOverrides 命令行覆盖；没有覆盖时传 {@link LlmCliOverrides#none()}
     * @return 合并后的配置
     */
    public static LlmConfig resolveLlm(LlmConfig fromFile, LlmCliOverrides cliOverrides) {
        LlmConfig file = fromFile != null ? fromFile : LlmConfig.defaults();
        LlmConfig defaults = LlmConfig.defaults();
        LlmCliOverrides cli = cliOverrides != null ? cliOverrides : LlmCliOverrides.none();

        String baseUrl = firstNonBlank(
                cli.baseUrl(),
                System.getenv("WECODE_BASE_URL"),
                file.baseUrl(),
                defaults.baseUrl()
        );
        String apiKey = firstNonBlank(
                cli.apiKey(),
                System.getenv("WECODE_API_KEY"),
                file.apiKey()
        );
        String model = firstNonBlank(
                cli.model(),
                System.getenv("WECODE_MODEL"),
                file.model(),
                defaults.model()
        );
        Double temperature = firstNonNull(
                cli.temperature(),
                parseDoubleEnv("WECODE_TEMPERATURE"),
                file.temperature(),
                defaults.temperature()
        );
        String reasoningEffort = firstNonBlank(
                cli.reasoningEffort(),
                System.getenv("WECODE_REASONING_EFFORT"),
                file.reasoningEffort()
        );
        Boolean returnThinking = firstNonNull(
                cli.returnThinking(),
                parseBooleanEnv("WECODE_RETURN_THINKING"),
                file.returnThinking()
        );
        Boolean sendThinking = firstNonNull(
                cli.sendThinking(),
                parseBooleanEnv("WECODE_SEND_THINKING"),
                file.sendThinking()
        );
        String thinkingFieldName = firstNonBlank(
                cli.thinkingFieldName(),
                System.getenv("WECODE_THINKING_FIELD_NAME"),
                file.thinkingFieldName()
        );

        return new LlmConfig(
                baseUrl,
                apiKey,
                model,
                temperature,
                reasoningEffort,
                returnThinking,
                sendThinking,
                thinkingFieldName
        );
    }

    /**
     * 校验运行前必填项。
     *
     * @param llm 已合并的配置
     */
    public static void requireApiKey(LlmConfig llm) {
        // 没有 key 时尽早失败，避免请求打到远端才报错
        if (llm == null || isBlank(llm.apiKey())) {
            throw new IllegalStateException(
                    "缺少 API Key：请设置环境变量 WECODE_API_KEY，或在 wecode.yml 的 llm.api-key 中配置"
            );
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            // 跳过空串，继续看下一层来源
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        if (values == null) {
            return null;
        }
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Double parseDoubleEnv(String key) {
        String raw = System.getenv(key);
        // 未设置环境变量
        if (isBlank(raw)) {
            return null;
        }
        try {
            return Double.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("环境变量 " + key + " 不是合法数字: " + raw, e);
        }
    }

    private static Boolean parseBooleanEnv(String key) {
        String raw = System.getenv(key);
        // 未设置环境变量
        if (isBlank(raw)) {
            return null;
        }
        return Boolean.valueOf(raw.trim());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
