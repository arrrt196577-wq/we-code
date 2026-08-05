package org.wecode.session.persistence.entity;

/**
 * 会话的持久化运行状态。
 * <p>
 * {@link #IDLE} 表示本次 Agent 运行已结束，但会话仍可继续；它不是不可恢复的完成态。
 */
public enum SessionStatus {

    /** 会话可被继续执行。 */
    IDLE,

    /** Agent 正在向该会话追加消息。进程异常退出后会保留该状态。 */
    RUNNING,

    /** 上一次运行未正常结束，需要在恢复前处理未完成的工具调用。 */
    INTERRUPTED,

    /** 会话已归档，不应再接受新的消息。 */
    ARCHIVED
}
