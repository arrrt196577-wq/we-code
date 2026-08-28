package org.wecode.agent;

import org.wecode.llm.model.Message;

import java.util.List;

/**
 * 为每次 LLM 请求提供当前会话消息的只读来源。
 * <p>
 * 生产实现可以从 SQLite 等持久化存储重建历史；Agent 模块不依赖具体存储实现。
 */
@FunctionalInterface
public interface ConversationMessageProvider {

    /**
     * 读取本轮请求应发送给 LLM 的完整、有序消息列表。
     *
     * @return 不可变或调用方可安全读取的消息快照
     */
    List<Message> loadMessages();
}
