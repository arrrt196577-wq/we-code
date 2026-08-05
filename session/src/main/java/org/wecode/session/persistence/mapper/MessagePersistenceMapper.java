package org.wecode.session.persistence.mapper;

import org.apache.ibatis.annotations.Param;
import org.wecode.session.persistence.entity.Message;

import java.util.List;

/**
 * 对话消息的追加与读取语句。
 * <p>
 * 消息表采用会话标识和序号组成的复合主键，因此不使用仅支持单主键语义的通用 CRUD 方法。
 */
public interface MessagePersistenceMapper {

    /**
     * 追加一条已通过会话乐观锁校验的消息。
     *
     * @param message 待写入消息
     * @return 成功插入的行数
     */
    int insert(Message message);

    /**
     * 按消息序号升序读取完整会话历史，用于完整恢复或历史查看。
     *
     * @param sessionId 会话唯一标识
     * @return 会话的全部持久化消息
     */
    List<Message> findAllBySessionId(@Param("sessionId") String sessionId);

    /**
     * 读取最近的若干条消息，并按正序返回，供一次 LLM 调用构建上下文。
     *
     * @param sessionId 会话唯一标识
     * @param limit     最大消息条数
     * @return 从旧到新的最近消息
     */
    List<Message> findRecentBySessionId(@Param("sessionId") String sessionId, @Param("limit") int limit);
}
