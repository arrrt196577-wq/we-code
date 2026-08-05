package org.wecode.session.persistence.mapper;

import org.apache.ibatis.annotations.Param;
import org.wecode.session.persistence.entity.Session;
import org.wecode.session.persistence.entity.SessionStatus;

import java.util.List;

/**
 * 会话元数据的持久化语句。
 * <p>
 * 追加消息时，调用方必须先执行 {@link #advanceForMessageAppend}，确认返回值为 {@code 1} 后，
 * 再调用消息 Mapper 插入消息；两条语句必须处于同一个数据库事务中。
 */
public interface SessionPersistenceMapper {

    /**
     * 创建一个尚未写入消息的会话。
     *
     * @param session 待持久化的会话元数据
     * @return 成功插入的行数
     */
    int insert(Session session);

    /**
     * 按会话标识读取会话元数据，用于进入或恢复会话。
     *
     * @param sessionId 会话唯一标识
     * @return 会话不存在时为 {@code null}
     */
    Session findById(@Param("sessionId") String sessionId);

    /**
     * 以乐观锁推进会话消息序号，并同步更新运行状态。
     *
     * @param sessionId           会话唯一标识
     * @param expectedVersion     调用方读取到的版本号
     * @param expectedLastSeq     调用方读取到的最后消息序号
     * @param newSeq              即将追加消息的序号
     * @param status              追加后的会话状态
     * @param updatedAt           更新时刻，UTC epoch milliseconds
     * @return 成功推进时返回 {@code 1}；返回 {@code 0} 表示发生并发写入、会话不存在或已归档
     */
    int advanceForMessageAppend(
            @Param("sessionId") String sessionId,
            @Param("expectedVersion") long expectedVersion,
            @Param("expectedLastSeq") long expectedLastSeq,
            @Param("newSeq") long newSeq,
            @Param("status") SessionStatus status,
            @Param("updatedAt") long updatedAt
    );

    /**
     * 查询上次进程退出时尚未正常结束的会话。
     *
     * @return 需要恢复或标记为中断的会话列表
     */
    List<Session> findRecoverable();

    /**
     * 启动恢复阶段将遗留的运行中会话标记为中断。
     *
     * @param updatedAt 状态更新时间，UTC epoch milliseconds
     * @return 被标记的会话数量
     */
    int markRunningAsInterrupted(@Param("updatedAt") long updatedAt);
}
