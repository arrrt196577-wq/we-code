package org.wecode.agent;

/**
 * 一次 AgentLoop 有序停止的原因。
 *
 * <p>该枚举只描述控制循环为何停止，不表示用户业务目标是否成功。</p>
 */
public enum AgentStopReason {

    /** 模型明确返回最终响应，且没有请求工具调用。 */
    FINAL_RESPONSE,

    /** Agent 已完成允许的最大模型轮次。 */
    MAX_STEPS,

    /** 模型因为输出长度限制停止，返回内容可能不完整。 */
    MODEL_OUTPUT_TRUNCATED,

    /** Provider 通过模型响应报告错误停止。 */
    MODEL_REPORTED_ERROR,

    /** 运行在安全边界上被用户或宿主取消。 */
    CANCELLED,

    /** 运行开始前被宿主校验规则拒绝。 */
    RUN_REJECTED
}
