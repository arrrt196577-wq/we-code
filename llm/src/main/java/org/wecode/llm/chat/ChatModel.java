package org.wecode.llm.chat;

import org.wecode.llm.model.LlmResponse;
import org.wecode.llm.model.Message;
import org.wecode.llm.model.ToolSpec;

import java.util.List;
import java.util.Objects;

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

    /**
     * 发起一轮带运行期选项的对话。
     * <p>
     * 默认实现保持既有 Provider 兼容性；尚未识别该选项的实现会沿用原始两参数调用。
     *
     * @param messages 当前会话消息（system / user / assistant / tool）
     * @param tools    向模型声明的工具；可为 empty
     * @param options  单次请求选项；不能为 {@code null}
     * @return 本轮完整结果（文本与可选 tool_calls）
     */
    default LlmResponse chat(List<Message> messages, List<ToolSpec> tools, ChatRequestOptions options) {
        Objects.requireNonNull(options, "options");
        // 旧实现没有额外选项能力时，保持原有请求语义。
        return chat(messages, tools);
    }
}
