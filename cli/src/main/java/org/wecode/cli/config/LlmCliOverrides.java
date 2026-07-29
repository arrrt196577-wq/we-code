package org.wecode.cli.config;

/**
 * 命令行对 LLM 配置的覆盖项；未指定的字段保持 {@code null}，不参与覆盖。
 * <p>
 * 用独立对象承载，避免 {@link ConfigResolver#resolveLlm} 参数随配置项膨胀。
 */
public record LlmCliOverrides(
        String baseUrl,
        String apiKey,
        String model,
        Double temperature,
        String reasoningEffort,
        Boolean returnThinking,
        Boolean sendThinking,
        String thinkingFieldName
) {
    /**
     * 无任何命令行覆盖。
     */
    public static LlmCliOverrides none() {
        return new LlmCliOverrides(null, null, null, null, null, null, null, null);
    }
}
