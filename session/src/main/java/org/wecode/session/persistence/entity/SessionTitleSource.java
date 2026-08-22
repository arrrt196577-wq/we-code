package org.wecode.session.persistence.entity;

/**
 * 会话标题的来源。
 */
public enum SessionTitleSource {

    /** 由首条用户消息截断得到的兜底标题。 */
    TEMPORARY,

    /** 由首条用户消息的一次模型命名生成。 */
    MODEL,

    /** 由用户通过 rename 明确设置。 */
    USER
}
