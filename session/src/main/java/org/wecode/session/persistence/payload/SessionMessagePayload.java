package org.wecode.session.persistence.payload;

import org.wecode.session.persistence.entity.SessionMessageType;

/**
 * 会话消息 JSON 载荷的封闭类型集合。
 *
 * <p>数据库中的 {@code message_type} 是类型判别字段，因此 JSON 内不再重复保存 Java 类名或类型字段。</p>
 */
public sealed interface SessionMessagePayload
        permits SystemPayload, UserPayload, AssistantPayload, CompactionPayload {

    /**
     * 返回当前载荷对应的持久化消息类型。
     *
     * @return 与载荷结构严格匹配的消息类型
     */
    SessionMessageType messageType();
}
