package org.wecode.agent;

/**
 * 上下文窗口估算的 MVP 配置。
 *
 * @param contextWindowLimitTokens          模型上下文窗口上限
 * @param requestOutputTokens               本次请求预留的输出 token；当前仅占位，尚未传递给 Provider
 * @param estimationSafetyBufferTokens      用于对冲字符估算误差的额外安全余量
 */
public record ContextWindowSettings(
        long contextWindowLimitTokens,
        long requestOutputTokens,
        long estimationSafetyBufferTokens
) {

    /** 当前 MVP 统一使用的上下文窗口上限。 */
    public static final long DEFAULT_CONTEXT_WINDOW_LIMIT_TOKENS = 300_000L;

    /** 当前 MVP 采用字符数除以四估算时的安全余量。 */
    public static final long DEFAULT_ESTIMATION_SAFETY_BUFFER_TOKENS = 20_000L;

    /**
     * 当前输出 token 预算仅为占位值；后续接入 Provider 的实际输出限制后替换。
     */
    public static final long DEFAULT_REQUEST_OUTPUT_TOKENS = 0L;

    /** 校验窗口、输出预留和安全余量形成可用的压缩阈值。 */
    public ContextWindowSettings {
        // 上下文窗口必须为正数，否则无法表示任何可发送的请求。
        if (contextWindowLimitTokens <= 0) {
            throw new IllegalArgumentException("contextWindowLimitTokens must be > 0");
        }
        // 输出预算允许为零，表示当前 MVP 尚未向 Provider 传递输出上限。
        if (requestOutputTokens < 0) {
            throw new IllegalArgumentException("requestOutputTokens must be >= 0");
        }
        // 安全余量必须为正数，避免字符估算误差直接耗尽上下文窗口。
        if (estimationSafetyBufferTokens <= 0) {
            throw new IllegalArgumentException("estimationSafetyBufferTokens must be > 0");
        }
        long reservedTokens = Math.addExact(requestOutputTokens, estimationSafetyBufferTokens);
        // 预留空间不能吞掉整个窗口，否则压缩阈值没有业务意义。
        if (reservedTokens >= contextWindowLimitTokens) {
            throw new IllegalArgumentException("reserved tokens must be less than context window limit");
        }
    }

    /**
     * 创建当前阶段的统一默认配置。
     *
     * @return 300k 窗口、20k 安全余量和零输出占位预算
     */
    public static ContextWindowSettings mvpDefaults() {
        return new ContextWindowSettings(
                DEFAULT_CONTEXT_WINDOW_LIMIT_TOKENS,
                DEFAULT_REQUEST_OUTPUT_TOKENS,
                DEFAULT_ESTIMATION_SAFETY_BUFFER_TOKENS
        );
    }
}
