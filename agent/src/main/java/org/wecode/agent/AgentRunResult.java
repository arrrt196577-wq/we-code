package org.wecode.agent;

import java.util.Objects;

/**
 * 一次 AgentLoop 有序结束后的结构化结果。
 *
 * @param assistantContent 最后一段非空 assistant 内容；非最终结束时可能为空或不完整
 * @param stopReason       AgentLoop 有序停止的原因
 * @param stopDetail       由宿主生成的诊断信息；没有时使用空字符串
 * @param completedSteps   已完成的模型轮次数
 */
public record AgentRunResult(
        String assistantContent,
        AgentStopReason stopReason,
        String stopDetail,
        int completedSteps
) {

    /** 校验结构化结果内部字段组合不会表达相互冲突的运行事实。 */
    public AgentRunResult {
        Objects.requireNonNull(assistantContent, "assistantContent");
        Objects.requireNonNull(stopReason, "stopReason");
        Objects.requireNonNull(stopDetail, "stopDetail");

        // 运行开始前可能停止，因此允许为零，但不能是负数。
        if (completedSteps < 0) {
            throw new IllegalArgumentException("completedSteps must be >= 0");
        }

        // 最终响应必须包含可以交付给调用方的模型正文。
        if (stopReason == AgentStopReason.FINAL_RESPONSE && assistantContent.isBlank()) {
            throw new IllegalArgumentException("FINAL_RESPONSE requires non-blank assistantContent");
        }

        // 最终响应必须来自至少一次已经完成的模型调用。
        if (stopReason == AgentStopReason.FINAL_RESPONSE && completedSteps == 0) {
            throw new IllegalArgumentException("FINAL_RESPONSE requires completedSteps > 0");
        }

        // 运行前拒绝不能已经产生模型轮次，并且必须保留可审计的拒绝原因。
        if (stopReason == AgentStopReason.RUN_REJECTED) {
            if (completedSteps != 0) {
                throw new IllegalArgumentException("RUN_REJECTED requires completedSteps == 0");
            }
            if (stopDetail.isBlank()) {
                throw new IllegalArgumentException("RUN_REJECTED requires non-blank stopDetail");
            }
        }
    }

    /**
     * 构造模型明确完成回复的运行结果。
     *
     * @param content        最终 assistant 正文
     * @param completedSteps 已完成的模型轮次数
     * @return 最终响应结果
     */
    public static AgentRunResult finalResponse(String content, int completedSteps) {
        return new AgentRunResult(content, AgentStopReason.FINAL_RESPONSE, "", completedSteps);
    }

    /**
     * 构造达到最大模型轮次的运行结果。
     *
     * @param lastAssistantContent 最后一段非空 assistant 内容；允许为空
     * @param completedSteps       已完成的模型轮次数
     * @param maxSteps             本次运行允许的最大模型轮次数
     * @return 最大轮次停止结果
     */
    public static AgentRunResult maxSteps(
            String lastAssistantContent,
            int completedSteps,
            int maxSteps
    ) {
        // 非正数上限无法形成有效的 AgentLoop 配置。
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps must be > 0");
        }
        // 达到上限时，已完成轮次数必须与配置上限一致。
        if (completedSteps != maxSteps) {
            throw new IllegalArgumentException("completedSteps must equal maxSteps");
        }
        return new AgentRunResult(
                lastAssistantContent,
                AgentStopReason.MAX_STEPS,
                "maxSteps=" + maxSteps,
                completedSteps
        );
    }

    /**
     * 构造模型输出因长度限制而截断的运行结果。
     *
     * @param partialContent 部分 assistant 内容；允许为空
     * @param completedSteps 已完成的模型轮次数
     * @return 模型输出截断结果
     */
    public static AgentRunResult modelOutputTruncated(String partialContent, int completedSteps) {
        return new AgentRunResult(
                partialContent,
                AgentStopReason.MODEL_OUTPUT_TRUNCATED,
                "finishReason=LENGTH",
                completedSteps
        );
    }

    /**
     * 构造 Provider 通过模型响应报告错误的运行结果。
     *
     * @param lastAssistantContent 最后一段非空 assistant 内容；允许为空
     * @param detail              Provider 或宿主提供的错误说明
     * @param completedSteps      已完成的模型轮次数
     * @return 模型报告错误结果
     */
    public static AgentRunResult modelReportedError(
            String lastAssistantContent,
            String detail,
            int completedSteps
    ) {
        return new AgentRunResult(
                lastAssistantContent,
                AgentStopReason.MODEL_REPORTED_ERROR,
                detail,
                completedSteps
        );
    }

    /**
     * 构造被用户或宿主取消的运行结果。
     *
     * @param lastAssistantContent 最后一段非空 assistant 内容；允许为空
     * @param detail              取消原因；没有时传入空字符串
     * @param completedSteps      已完成的模型轮次数
     * @return 取消结果
     */
    public static AgentRunResult cancelled(
            String lastAssistantContent,
            String detail,
            int completedSteps
    ) {
        return new AgentRunResult(
                lastAssistantContent,
                AgentStopReason.CANCELLED,
                detail,
                completedSteps
        );
    }

    /**
     * 构造运行开始前被校验规则拒绝的结果。
     *
     * @param detail 非空拒绝原因
     * @return 尚未执行模型调用的拒绝结果
     */
    public static AgentRunResult runRejected(String detail) {
        return new AgentRunResult("", AgentStopReason.RUN_REJECTED, detail, 0);
    }

    /**
     * 判断模型是否明确产生了最终响应。
     *
     * @return 仅在停止原因为 FINAL_RESPONSE 时返回 true
     */
    public boolean hasFinalResponse() {
        return stopReason == AgentStopReason.FINAL_RESPONSE;
    }
}
