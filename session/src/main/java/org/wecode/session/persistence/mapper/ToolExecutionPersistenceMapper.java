package org.wecode.session.persistence.mapper;

import org.apache.ibatis.annotations.Param;
import org.wecode.session.persistence.entity.ToolExecutionRecord;
import org.wecode.session.persistence.entity.ToolExecutionStatus;

import java.util.List;

/**
 * 对 {@code tool_execution} 的创建、领取、完成和恢复访问语句。
 */
public interface ToolExecutionPersistenceMapper {

    /**
     * 插入模型返回的一次待执行工具调用。
     *
     * @param execution 待写入的工具执行记录，初始状态应为 {@code PENDING}
     * @return 成功插入的行数
     */
    int insert(ToolExecutionRecord execution);

    /**
     * 按模型原始调用顺序读取某条 assistant 消息的工具调用。
     *
     * @param assistantMessageId assistant 消息标识
     * @return 按 callIndex 升序排列的工具调用
     */
    List<ToolExecutionRecord> findByAssistantMessageId(
            @Param("assistantMessageId") String assistantMessageId
    );

    /**
     * 通过乐观锁领取一条待执行调用并建立租约。
     *
     * @param id         工具执行记录标识
     * @param revision   调用方读取到的版本号
     * @param leaseToken 本次执行唯一租约令牌
     * @param leaseUntil 租约到期时刻
     * @param startedAt  执行开始时刻
     * @param updatedAt  最后更新时间
     * @return 成功领取时返回 1；否则返回 0
     */
    int claimPending(
            @Param("id") String id,
            @Param("revision") long revision,
            @Param("leaseToken") String leaseToken,
            @Param("leaseUntil") long leaseUntil,
            @Param("startedAt") long startedAt,
            @Param("updatedAt") long updatedAt
    );

    /**
     * 以当前租约完成一次工具调用。
     *
     * @param id         工具执行记录标识
     * @param revision   调用方读取到的版本号
     * @param leaseToken 当前执行租约令牌
     * @param status     仅允许 {@code SUCCEEDED}、{@code FAILED} 或 {@code UNKNOWN}
     * @param resultJson 工具结果或错误详情 JSON
     * @param resultPayloadVersion 工具结果 JSON 的结构版本
     * @param finishedAt 终态写入时刻
     * @param updatedAt  最后更新时间
     * @return 成功更新时返回 1；否则返回 0
     */
    int completeRunning(
            @Param("id") String id,
            @Param("revision") long revision,
            @Param("leaseToken") String leaseToken,
            @Param("status") ToolExecutionStatus status,
            @Param("resultJson") String resultJson,
            @Param("resultPayloadVersion") int resultPayloadVersion,
            @Param("finishedAt") long finishedAt,
            @Param("updatedAt") long updatedAt
    );

    /**
     * 将超出租约的运行中工具标记为 UNKNOWN，避免对有副作用的工具盲目重试。
     *
     * @param now        当前时间
     * @param resultJson 用于恢复诊断的 JSON 详情
     * @param resultPayloadVersion 恢复诊断 JSON 的结构版本
     * @return 被标记的记录数量
     */
    int markExpiredRunningAsUnknown(
            @Param("now") long now,
            @Param("resultJson") String resultJson,
            @Param("resultPayloadVersion") int resultPayloadVersion
    );
}
