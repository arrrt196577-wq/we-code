package org.wecode.agent;

import org.wecode.llm.model.Message;
import org.wecode.llm.model.ToolSpec;

import java.util.List;
import java.util.Objects;

/**
 * 根据当前请求内容和 MVP 预算判断是否需要执行上下文压缩。
 */
public final class ContextWindowPolicy {

    private final CharacterTokenEstimator tokenEstimator;
    private final ContextWindowSettings settings;

    /**
     * @param tokenEstimator 字符 token 估算器
     * @param settings       上下文窗口预算配置
     */
    public ContextWindowPolicy(CharacterTokenEstimator tokenEstimator, ContextWindowSettings settings) {
        this.tokenEstimator = Objects.requireNonNull(tokenEstimator, "tokenEstimator");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /**
     * 创建当前阶段统一使用的 300k 上下文窗口策略。
     *
     * @return 字符估算和 20k 安全余量组成的 MVP 策略
     */
    public static ContextWindowPolicy mvpDefaults() {
        return new ContextWindowPolicy(new CharacterTokenEstimator(), ContextWindowSettings.mvpDefaults());
    }

    /**
     * 计算本轮模型请求的上下文窗口使用量。
     *
     * @param messages 当前会话消息
     * @param tools    当前请求声明的工具定义
     * @return 用于决定是否需要压缩的窗口使用量
     */
    public ContextWindowUsage evaluate(List<Message> messages, List<ToolSpec> tools) {
        long estimatedInputTokens = tokenEstimator.estimateInputTokens(messages, tools);
        long reservedTokens = Math.addExact(
                settings.requestOutputTokens(),
                settings.estimationSafetyBufferTokens()
        );
        long compactionTriggerTokens = settings.contextWindowLimitTokens() - reservedTokens;
        long remainingTokens = compactionTriggerTokens - estimatedInputTokens;
        // 输入到达阈值时仅标记压缩需求，当前阶段不在此处改写会话消息。
        boolean compactionRequired = estimatedInputTokens >= compactionTriggerTokens;
        return new ContextWindowUsage(
                estimatedInputTokens,
                settings.contextWindowLimitTokens(),
                settings.requestOutputTokens(),
                settings.estimationSafetyBufferTokens(),
                compactionTriggerTokens,
                remainingTokens,
                compactionRequired
        );
    }
}
