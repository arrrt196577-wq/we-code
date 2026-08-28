package org.wecode.llm.model;

import java.util.List;
import java.util.Objects;

/**
 * 一轮 provider chat 的结果。
 *
 * @param content       assistant 正文；可为 null
 * @param toolCalls     模型请求的工具调用；无则为 empty
 * @param finishReason  停止原因
 * @param thinking      推理/思考文本（如 reasoning_content）；没有则为 null
 * @param usage         Provider 返回的本次 token 用量；Provider 未返回或格式不完整时为 null
 */
public record LlmResponse(
        String content,
        List<ToolCall> toolCalls,
        FinishReason finishReason,
        String thinking,
        LlmUsage usage
) {

    public LlmResponse {
        Objects.requireNonNull(finishReason, "finishReason");
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        // TOOL_CALLS 必须带至少一个 tool call
        if (finishReason == FinishReason.TOOL_CALLS && toolCalls.isEmpty()) {
            throw new IllegalArgumentException("TOOL_CALLS requires non-empty toolCalls");
        }
    }

    /**
     * 无 usage 字段时的便捷构造，兼容既有 Provider 实现和调用方。
     *
     * @param content      正文
     * @param toolCalls    工具调用
     * @param finishReason 停止原因
     * @param thinking     推理/思考文本
     */
    public LlmResponse(String content, List<ToolCall> toolCalls, FinishReason finishReason, String thinking) {
        this(content, toolCalls, finishReason, thinking, null);
    }

    /**
     * 无思考字段时的便捷构造。
     *
     * @param content      正文
     * @param toolCalls    工具调用
     * @param finishReason 停止原因
     */
    public LlmResponse(String content, List<ToolCall> toolCalls, FinishReason finishReason) {
        this(content, toolCalls, finishReason, null, null);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }

    public boolean hasThinking() {
        return thinking != null && !thinking.isBlank();
    }
}
