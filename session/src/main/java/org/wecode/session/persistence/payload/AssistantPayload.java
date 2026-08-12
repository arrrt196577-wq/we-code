package org.wecode.session.persistence.payload;

import org.wecode.llm.model.LlmResponse;
import org.wecode.llm.model.Message;
import org.wecode.llm.model.ToolCall;
import org.wecode.session.persistence.entity.SessionMessageType;

import java.util.List;
import java.util.Objects;

/**
 * assistant 响应中不与 {@code tool_execution} 重复的不可变部分。
 *
 * @param content          assistant 可见正文；仅工具调用响应时可为 {@code null}
 * @param reasoningContent Provider 要求后续轮次回传的推理文本；没有时为 {@code null}
 * @param finishReason     模型停止原因
 */
public record AssistantPayload(
        String content,
        String reasoningContent,
        AssistantFinishReason finishReason
) implements SessionMessagePayload {

    /** 校验 assistant 必须具备明确停止原因。 */
    public AssistantPayload {
        finishReason = Objects.requireNonNull(finishReason, "finishReason");
    }

    /**
     * 从本轮 LLM 响应提取不可变载荷，工具调用由独立执行表保存。
     *
     * @param response 当前 LLM 响应
     * @return 可持久化 assistant 载荷
     */
    public static AssistantPayload fromResponse(LlmResponse response) {
        Objects.requireNonNull(response, "response");
        // 这里只提取响应正文、推理内容和停止原因，避免与工具执行表形成双重数据源。
        return new AssistantPayload(
                response.content(),
                response.thinking(),
                AssistantFinishReason.fromRuntime(response.finishReason())
        );
    }

    @Override
    public SessionMessageType messageType() {
        // assistant 载荷只能写入 ASSISTANT 类型的历史事件。
        return SessionMessageType.ASSISTANT;
    }

    /**
     * 结合工具执行表中的原始调用重建模型消息。
     *
     * @param toolCalls 按 {@code call_index} 升序恢复的工具调用
     * @return 可直接进入模型上下文的 assistant 消息
     */
    public Message toMessage(List<ToolCall> toolCalls) {
        List<ToolCall> calls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        // TOOL_CALLS 与工具子记录必须互相印证，防止恢复出残缺历史。
        if (finishReason == AssistantFinishReason.TOOL_CALLS && calls.isEmpty()) {
            throw new IllegalArgumentException("TOOL_CALLS assistant requires persisted tool calls");
        }
        // 非工具停止原因不能携带工具调用，否则存在双重事实冲突。
        if (finishReason != AssistantFinishReason.TOOL_CALLS && !calls.isEmpty()) {
            throw new IllegalArgumentException("Only TOOL_CALLS assistant may have persisted tool calls");
        }
        // 没有正文且没有工具调用的响应无法形成有效的会话历史。
        if ((content == null || content.isBlank()) && calls.isEmpty()) {
            throw new IllegalArgumentException("Assistant must have content or persisted tool calls");
        }
        // 推理内容按 Provider 原样回传，不能混入用户可见正文。
        return Message.assistant(content, calls, reasoningContent);
    }
}
