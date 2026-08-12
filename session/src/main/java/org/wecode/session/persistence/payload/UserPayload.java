package org.wecode.session.persistence.payload;

import org.wecode.llm.model.Message;
import org.wecode.session.persistence.entity.SessionMessageType;

import java.util.Objects;

/**
 * 当前纯文本 MVP 的 user 消息载荷。
 *
 * @param content 用户提交的原始文本
 */
public record UserPayload(String content) implements SessionMessagePayload {

    /** 校验用户输入可以形成有效的模型消息。 */
    public UserPayload {
        Objects.requireNonNull(content, "content");
        // 当前模型不接受没有执行语义的空白用户消息。
        if (content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
    }

    @Override
    public SessionMessageType messageType() {
        // user 载荷只能写入 USER 类型的历史事件。
        return SessionMessageType.USER;
    }

    /**
     * 将持久化载荷恢复为模型消息。
     *
     * @return 可直接进入模型上下文的 user 消息
     */
    public Message toMessage() {
        // 使用原始用户文本恢复上下文。
        return Message.user(content);
    }
}
