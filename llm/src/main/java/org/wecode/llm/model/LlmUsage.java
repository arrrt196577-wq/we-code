package org.wecode.llm.model;

/**
 * Provider 返回的单次模型请求 token 用量。
 * <p>
 * 核心字段使用统一的输入、输出命名；缓存和推理字段保留为可选明细，
 * 以兼容 OpenAI Chat Completions 协议的不同实现。
 *
 * @param inputTokens         本次请求输入上下文的 token 数
 * @param outputTokens        本次模型生成的 token 数
 * @param totalTokens         Provider 统计的本次总 token 数
 * @param cachedInputTokens   命中 Provider 上下文缓存的输入 token 数；未提供时为 {@code null}
 * @param uncachedInputTokens 未命中 Provider 上下文缓存的输入 token 数；未提供时为 {@code null}
 * @param reasoningTokens     模型推理阶段生成的 token 数；未提供时为 {@code null}
 */
public record LlmUsage(
        long inputTokens,
        long outputTokens,
        long totalTokens,
        Long cachedInputTokens,
        Long uncachedInputTokens,
        Long reasoningTokens
) {

    /** 校验 Provider 返回的 token 统计不存在负数。 */
    public LlmUsage {
        // 核心用量为必填统计值，负数没有有效业务语义。
        requireNonNegative(inputTokens, "inputTokens");
        requireNonNegative(outputTokens, "outputTokens");
        requireNonNegative(totalTokens, "totalTokens");
        // 可选明细缺失时保留 null；存在时同样不允许为负数。
        requireOptionalNonNegative(cachedInputTokens, "cachedInputTokens");
        requireOptionalNonNegative(uncachedInputTokens, "uncachedInputTokens");
        requireOptionalNonNegative(reasoningTokens, "reasoningTokens");
    }

    /** 校验一个必填 token 数为非负。 */
    private static void requireNonNegative(long value, String fieldName) {
        // Provider 返回负数表示协议数据异常，不能进入领域模型。
        if (value < 0) {
            throw new IllegalArgumentException(fieldName + " must be >= 0");
        }
    }

    /** 校验一个可选 token 明细在存在时为非负。 */
    private static void requireOptionalNonNegative(Long value, String fieldName) {
        // 未提供的兼容字段无需校验。
        if (value == null) {
            return;
        }
        requireNonNegative(value, fieldName);
    }
}
