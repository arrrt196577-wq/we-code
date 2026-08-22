package org.wecode.agent;

import org.wecode.llm.model.LlmResponse;
import org.wecode.llm.model.ToolCall;
import org.wecode.tools.result.ToolResult;

/**
 * 观察 Agent 运行中的可持久化边界。
 * <p>
 * 默认实现由 {@link #NO_OP} 提供，Agent 模块不直接依赖具体数据库实现。
 */
public interface AgentExecutionListener {

    /** 不执行额外动作的默认监听器。 */
    AgentExecutionListener NO_OP = new AgentExecutionListener() {
    };

    /**
     * 模型响应已写入内存 session、工具尚未执行时调用。
     *
     * @param response 本轮完整模型响应
     */
    default void onAssistantResponse(LlmResponse response) {
    }

    /**
     * 每次模型请求前计算完当前窗口使用量时调用。
     *
     * @param usage 本轮请求的上下文窗口估算结果
     */
    default void onContextWindowUsage(ContextWindowUsage usage) {
    }

    /**
     * 工具即将实际执行前调用，用于先持久化领取状态。
     *
     * @param call      模型工具调用
     * @param callIndex 本轮工具调用下标
     */
    default void onToolExecutionStarting(ToolCall call, int callIndex) {
    }

    /**
     * 工具得到 observation 后调用，用于持久化结果。
     *
     * @param call      模型工具调用
     * @param callIndex 本轮工具调用下标
     * @param result    工具执行结果
     */
    default void onToolExecutionCompleted(ToolCall call, int callIndex, ToolResult result) {
    }
}
