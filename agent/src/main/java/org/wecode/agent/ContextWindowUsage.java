package org.wecode.agent;

/**
 * 单次模型请求前的上下文窗口估算结果。
 *
 * @param estimatedInputTokens            使用字符估算得到的输入 token 数
 * @param contextWindowLimitTokens        当前上下文窗口上限
 * @param requestOutputTokens             本次请求预留的输出 token 数
 * @param estimationSafetyBufferTokens    字符估算误差的安全余量
 * @param compactionTriggerTokens         达到该输入 token 数时应执行压缩
 * @param remainingTokensBeforeCompaction 距离压缩阈值的剩余 token 数，负数表示已超出
 * @param compactionRequired              当前是否达到压缩阈值
 */
public record ContextWindowUsage(
        long estimatedInputTokens,
        long contextWindowLimitTokens,
        long requestOutputTokens,
        long estimationSafetyBufferTokens,
        long compactionTriggerTokens,
        long remainingTokensBeforeCompaction,
        boolean compactionRequired
) {

    /** 校验上下文使用量的计算结果保持自洽。 */
    public ContextWindowUsage {
        // 输入估算不能为负数。
        if (estimatedInputTokens < 0) {
            throw new IllegalArgumentException("estimatedInputTokens must be >= 0");
        }
        // 窗口与预留值必须来自已校验的设置。
        if (contextWindowLimitTokens <= 0 || requestOutputTokens < 0 || estimationSafetyBufferTokens <= 0) {
            throw new IllegalArgumentException("invalid context window usage settings");
        }
        // 阈值必须落在上下文窗口内。
        if (compactionTriggerTokens < 0 || compactionTriggerTokens >= contextWindowLimitTokens) {
            throw new IllegalArgumentException("invalid compactionTriggerTokens");
        }
        // 布尔状态必须与输入估算和阈值保持一致。
        if (compactionRequired != (estimatedInputTokens >= compactionTriggerTokens)) {
            throw new IllegalArgumentException("compactionRequired does not match token usage");
        }
    }

    /**
     * 生成不包含 Prompt 正文的可观测日志。
     *
     * @return 当前窗口估算摘要
     */
    public String toLogMessage() {
        return "context: estimatedInputTokens=" + estimatedInputTokens
                + ", contextWindowLimitTokens=" + contextWindowLimitTokens
                + ", requestOutputTokens=" + requestOutputTokens
                + ", safetyBufferTokens=" + estimationSafetyBufferTokens
                + ", compactionTriggerTokens=" + compactionTriggerTokens
                + ", remainingTokensBeforeCompaction=" + remainingTokensBeforeCompaction
                + ", compactionRequired=" + compactionRequired;
    }
}
