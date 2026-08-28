package org.wecode.session.persistence.mapper;

import org.apache.ibatis.annotations.Param;
import org.wecode.session.persistence.entity.SessionMessageRecord;

import java.util.List;

/**
 * {@code session_message} 的不可变历史事件访问语句。
 */
public interface SessionMessagePersistenceMapper {

    /**
     * 追加一条已通过会话乐观锁校验的历史事件。
     *
     * @param message 待写入事件
     * @return 成功插入的行数
     */
    int insert(SessionMessageRecord message);

    /**
     * 读取完整历史，用于无压缩节点的 Prompt 重建或审计。
     *
     * @param sessionId 会话标识
     * @return 按会话序号升序排列的历史事件
     */
    List<SessionMessageRecord> findAllBySessionId(@Param("sessionId") String sessionId);

    /**
     * 读取会话创建时写入的首条固定 system 消息。
     *
     * @param sessionId 会话标识
     * @return 最早的 system 消息；会话历史损坏或为空时为 {@code null}
     */
    SessionMessageRecord findFirstSystemMessage(@Param("sessionId") String sessionId);

    /**
     * 读取压缩节点之后的原始历史尾部，不包含 compaction 事件本身。
     *
     * @param sessionId     会话标识
     * @param afterSequence 压缩覆盖的最后序号
     * @return 按会话序号升序排列的原始历史尾部
     */
    List<SessionMessageRecord> findAfterSequenceExcludingCompaction(
            @Param("sessionId") String sessionId,
            @Param("afterSequence") long afterSequence
    );

    /**
     * 查询会话内最后追加的压缩检查点。
     *
     * @param sessionId 会话标识
     * @return 不存在压缩检查点时为 {@code null}
     */
    SessionMessageRecord findLatestCompaction(@Param("sessionId") String sessionId);
}
