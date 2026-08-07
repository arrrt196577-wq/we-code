package org.wecode.agent;

import org.wecode.llm.chat.ChatModel;
import org.wecode.llm.model.LlmResponse;
import org.wecode.llm.model.Message;
import org.wecode.llm.model.ToolCall;
import org.wecode.llm.model.ToolSpec;
import org.wecode.session.Session;
import org.wecode.tools.registry.ToolRegistry;
import org.wecode.tools.result.ToolResult;
import org.wecode.tools.spi.ToolContext;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Agent 编排：chat → 执行 tool → 回灌 observation，直到无 tool call 或达最大步数。
 */
public final class AgentLoop {

    /** 默认最大 LLM 轮次（每轮一次 chat）。 */
    public static final int DEFAULT_MAX_STEPS = 16;

    private final ChatModel chatModel;
    private final ToolRegistry toolRegistry;
    private final ToolContext toolContext;
    private final int maxSteps;
    private final Consumer<String> stepLogger;

    /**
     * @param chatModel     真 LLM 或测试替身
     * @param toolRegistry  已注册工具
     * @param toolContext   项目根目录上下文
     * @param maxSteps      最大 chat 轮次；须 &gt; 0
     * @param stepLogger    每步进度日志；可为 null（静默）
     */
    public AgentLoop(
            ChatModel chatModel,
            ToolRegistry toolRegistry,
            ToolContext toolContext,
            int maxSteps,
            Consumer<String> stepLogger
    ) {
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry");
        this.toolContext = Objects.requireNonNull(toolContext, "toolContext");
        // 非法步数会导致死循环或立刻退出，启动前校验
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps must be > 0");
        }
        this.maxSteps = maxSteps;
        this.stepLogger = stepLogger != null ? stepLogger : msg -> {
        };
    }

    /**
     * 使用默认最大步数、无日志。
     */
    public AgentLoop(ChatModel chatModel, ToolRegistry toolRegistry, ToolContext toolContext) {
        this(chatModel, toolRegistry, toolContext, DEFAULT_MAX_STEPS, null);
    }

    /**
     * 在已有 session（通常已含 system + user）上运行直到停止。
     *
     * @param session 会话消息列表
     * @return 最终助手文本；可能为空串
     */
    public String run(Session session) {
        Objects.requireNonNull(session, "session");
        List<ToolSpec> tools = toolRegistry.listSpecs();
        String lastContent = "";

        for (int step = 1; step <= maxSteps; step++) {
            stepLogger.accept("step " + step + "/" + maxSteps + ": chatting…");
            LlmResponse response = chatModel.chat(session.messages(), tools);

            // 先把本轮 assistant（含可能的 tool_calls）写入历史
            // 保存本轮思考内容，确保要求回传推理字段的 Provider 能完成后续工具调用。
            session.append(Message.assistant(response.content(), response.toolCalls(), response.thinking()));
            if (response.content() != null && !response.content().isBlank()) {
                lastContent = response.content();
            }

            // 无工具调用 → 任务结束
            if (!response.hasToolCalls()) {
                stepLogger.accept("step " + step + ": stop (no tool calls)");
                return lastContent == null ? "" : lastContent;
            }

            // 串行执行本轮全部 tool call，再进入下一轮 chat
            for (ToolCall call : response.toolCalls()) {
                // 打印模型传入的参数 JSON，便于对照调试
                stepLogger.accept(
                        "tool: " + call.name()
                                + " id=" + call.id()
                                + " args=" + call.argumentsJson()
                );
                ToolResult result = toolRegistry.execute(
                        call.id(),
                        call.name(),
                        call.argumentsJson(),
                        toolContext
                );
                // 失败也回灌，让模型自行纠错
                session.append(Message.tool(result.toolCallId(), result.content()));
            }
        }

        // 步数耗尽：带上说明，避免调用方误以为正常结束
        stepLogger.accept("stopped: reached maxSteps=" + maxSteps);
        if (lastContent == null || lastContent.isBlank()) {
            return "Stopped: reached max steps (" + maxSteps + ") without a final answer.";
        }
        return lastContent + "\n\n[Stopped: reached max steps (" + maxSteps + ")]";
    }
}
