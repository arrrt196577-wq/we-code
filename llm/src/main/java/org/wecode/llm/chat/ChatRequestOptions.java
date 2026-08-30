package org.wecode.llm.chat;

/**
 * 单次 Chat 请求的可选运行参数。
 *
 * @param maxOutputTokens 限制模型本次可生成的最大输出 token；为 {@code null} 时由 Provider 使用默认值
 */
public record ChatRequestOptions(Integer maxOutputTokens) {

    /** 不覆盖 Provider 默认请求参数的选项实例。 */
    private static final ChatRequestOptions DEFAULTS = new ChatRequestOptions(null);

    /** 校验可选输出 token 上限的取值范围。 */
    public ChatRequestOptions {
        // 未指定上限时保留 Provider 的既有行为。
        if (maxOutputTokens != null && maxOutputTokens <= 0) {
            throw new IllegalArgumentException("maxOutputTokens must be > 0 when specified");
        }
    }

    /**
     * @return 不覆盖 Provider 默认参数的请求选项
     */
    public static ChatRequestOptions defaults() {
        return DEFAULTS;
    }

    /**
     * 创建带最大输出 token 限制的请求选项。
     *
     * @param maxOutputTokens 最大输出 token；必须大于 {@code 0}
     * @return 已校验的请求选项
     */
    public static ChatRequestOptions withMaxOutputTokens(int maxOutputTokens) {
        return new ChatRequestOptions(maxOutputTokens);
    }
}
