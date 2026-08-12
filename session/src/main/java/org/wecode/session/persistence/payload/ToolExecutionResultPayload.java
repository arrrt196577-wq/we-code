package org.wecode.session.persistence.payload;

import org.wecode.llm.model.Message;

import java.util.Objects;

/**
 * 工具执行完成后需要持久化并回灌给模型的结果载荷。
 *
 * @param content 工具 observation 原文；允许空字符串，但不能为 {@code null}
 */
public record ToolExecutionResultPayload(String content) {

    /** 工具结果必须保留一个确定的 observation 文本。 */
    public ToolExecutionResultPayload {
        content = Objects.requireNonNull(content, "content");
    }

    /**
     * 结合工具执行记录中的调用标识恢复 tool 消息。
     *
     * @param toolCallId 发起该执行的模型工具调用标识
     * @return 可直接进入模型上下文的 tool 消息
     */
    public Message toMessage(String toolCallId) {
        // callId 属于工具执行元数据，不在结果 JSON 中重复保存。
        return Message.tool(toolCallId, content);
    }
}
