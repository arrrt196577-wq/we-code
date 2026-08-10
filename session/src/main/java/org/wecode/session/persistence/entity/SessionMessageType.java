package org.wecode.session.persistence.entity;

/**
 * {@code session_message} 的事件类型。
 * <p>
 * 工具调用与执行结果由 {@code tool_execution} 表维护，因此不在本枚举中单列工具类型。
 */
public enum SessionMessageType {

    /** 稳定的系统提示词。 */
    SYSTEM,

    /** 用户提交的任务或追问。 */
    USER,

    /** 模型返回的不可变 assistant 响应。 */
    ASSISTANT,

    /** 可替代一段旧历史的上下文压缩检查点。 */
    COMPACTION
}
