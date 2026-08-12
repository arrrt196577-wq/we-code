package org.wecode.session.persistence.payload;

import org.wecode.llm.model.Message;
import org.wecode.session.persistence.entity.SessionMessageType;

import java.util.Objects;

/**
 * 可被原样恢复的 system 消息载荷。
 *
 * @param content       实际发送给模型的完整 system 文本
 * @param promptVersion 生成该文本的 Prompt 规则版本，仅用于审计和兼容性诊断
 */
public record SystemPayload(String content, String promptVersion) implements SessionMessagePayload {

    /** 校验 system 文本和规则版本均可用于可靠恢复。 */
    public SystemPayload {
        content = requireNonBlank(content, "content");
        promptVersion = requireNonBlank(promptVersion, "promptVersion");
    }

    @Override
    public SessionMessageType messageType() {
        // system 载荷只能写入 SYSTEM 类型的历史事件。
        return SessionMessageType.SYSTEM;
    }

    /**
     * 将持久化载荷恢复为模型消息，避免重新生成已经可能发生版本变化的 system prompt。
     *
     * @return 可直接进入模型上下文的 system 消息
     */
    public Message toMessage() {
        // 恢复时必须使用持久化的原始文本。
        return Message.system(content);
    }

    /** 校验必须存在的非空白字段。 */
    private static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        // 空白文本无法形成有效的模型消息或版本标识。
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        // 保留原始文本，避免序列化前擅自改变 Prompt 内容。
        return value;
    }
}
