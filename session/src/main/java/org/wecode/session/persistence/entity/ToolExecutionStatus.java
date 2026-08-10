package org.wecode.session.persistence.entity;

/**
 * 一次工具调用的执行状态。
 */
public enum ToolExecutionStatus {

    /** 已持久化但尚未被执行器领取。 */
    PENDING,

    /** 已由持有租约的执行器领取，执行结果尚未写回。 */
    RUNNING,

    /** 工具已成功执行，结果已持久化。 */
    SUCCEEDED,

    /** 工具已完成但返回了可确定的失败结果。 */
    FAILED,

    /** 进程中断导致工具是否产生副作用无法确定。 */
    UNKNOWN,

    /** 会话取消或归档前，尚未执行的工具被显式取消。 */
    CANCELLED
}
