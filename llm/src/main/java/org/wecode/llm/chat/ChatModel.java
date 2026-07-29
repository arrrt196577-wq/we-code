package org.wecode.llm.chat;

import org.wecode.llm.model.LlmResponse;
import org.wecode.llm.model.Message;
import org.wecode.llm.model.ToolSpec;

import java.util.List;

/**
 * 能完成「一轮」Chat Completions 的模型能力。
 * <p>
 * 调用返回时本轮已结束；实现内部可以是 SSE 流式聚合，对外仍是完整 {@link LlmResponse}。
 * 不负责读配置文件或环境变量，连接参数由构造注入。
 */
public interface ChatModel {

    /**
     * 发起一轮对话（可带工具定义）。
     *
     * @param messages 当前会话消息（system / user / assistant / tool）
     * @param tools    向模型声明的工具；可为 empty
     * @return 本轮完整结果（文本与可选 tool_calls）
     */
    LlmResponse chat(List<Message> messages, List<ToolSpec> tools);
}
