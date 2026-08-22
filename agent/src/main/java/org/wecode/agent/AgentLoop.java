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
    private final AgentExecutionListener executionListener;
    private final ContextWindowPolicy contextWindowPolicy;

    /**
     * @param chatModel     真 LLM 或测试替身
     * @param toolRegistry  已注册工具
     * @param toolContext   工作区路径上下文
     * @param maxSteps      最大 chat 轮次；须 &gt; 0
     * @param stepLogger    每步进度日志；可为 null（静默）
     */
    public AgentLoop(
            ChatModel chatModel,
            ToolRegistry toolRegistry,
            ToolContext toolContext,
            int maxSteps,
            Consumer<String> stepLogger,
            AgentExecutionListener executionListener
    ) {
        this(
                chatModel,
                toolRegistry,
                toolContext,
                maxSteps,
                stepLogger,
                executionListener,
                ContextWindowPolicy.mvpDefaults()
        );
    }

    /**
     * 使用指定的上下文窗口策略创建 Agent。
     *
     * @param chatModel           模型实现
     * @param toolRegistry        已注册工具
     * @param toolContext         工作区工具上下文
     * @param maxSteps            最大模型轮次
     * @param stepLogger          可选进度日志
     * @param executionListener   Agent 执行事件观察器
     * @param contextWindowPolicy 每轮请求前执行的上下文窗口策略
     */
    public AgentLoop(
            ChatModel chatModel,
            ToolRegistry toolRegistry,
            ToolContext toolContext,
            int maxSteps,
            Consumer<String> stepLogger,
            AgentExecutionListener executionListener,
            ContextWindowPolicy contextWindowPolicy
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
        this.executionListener = executionListener == null ? AgentExecutionListener.NO_OP : executionListener;
        this.contextWindowPolicy = Objects.requireNonNull(contextWindowPolicy, "contextWindowPolicy");
    }

    /**
     * 使用指定步数和日志创建 Agent，不额外观察执行事件。
     *
     * @param chatModel    模型实现
     * @param toolRegistry 已注册工具
     * @param toolContext  工作区工具上下文
     * @param maxSteps     最大模型轮次
     * @param stepLogger   可选进度日志
     */
    public AgentLoop(
            ChatModel chatModel,
            ToolRegistry toolRegistry,
            ToolContext toolContext,
            int maxSteps,
            Consumer<String> stepLogger
    ) {
        this(chatModel, toolRegistry, toolContext, maxSteps, stepLogger, AgentExecutionListener.NO_OP);
    }

    /**
     * 使用默认最大步数、无日志。
     */
    public AgentLoop(ChatModel chatModel, ToolRegistry toolRegistry, ToolContext toolContext) {
        this(chatModel, toolRegistry, toolContext, DEFAULT_MAX_STEPS, null, AgentExecutionListener.NO_OP);
    }

    /**
     * 使用默认步数创建带执行事件观察的 Agent。
     *
     * @param chatModel         模型实现
     * @param toolRegistry      已注册工具
     * @param toolContext       工作区工具上下文
     * @param executionListener assistant 与工具执行事件观察器
     */
    public AgentLoop(
            ChatModel chatModel,
            ToolRegistry toolRegistry,
            ToolContext toolContext,
            AgentExecutionListener executionListener
    ) {
        this(chatModel, toolRegistry, toolContext, DEFAULT_MAX_STEPS, null, executionListener);
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
            // 每轮请求前重新计算，因为工具调用和工具结果会持续改变有效上下文。
            ContextWindowUsage contextWindowUsage = contextWindowPolicy.evaluate(session.messages(), tools);
            stepLogger.accept(contextWindowUsage.toLogMessage());
            executionListener.onContextWindowUsage(contextWindowUsage);
            // 当前阶段仅暴露压缩需求，真正压缩将在后续功能中接入此边界。
            if (contextWindowUsage.compactionRequired()) {
                stepLogger.accept("context: compaction required; continuing in observation-only mode");
            }
            stepLogger.accept("step " + step + "/" + maxSteps + ": chatting…");
            LlmResponse response = chatModel.chat(session.messages(), tools);

            // 先把本轮 assistant（含可能的 tool_calls）写入历史
            // 保存本轮思考内容，确保要求回传推理字段的 Provider 能完成后续工具调用。
            session.append(Message.assistant(response.content(), response.toolCalls(), response.thinking()));
            // assistant 与待执行工具必须在实际副作用发生前交给持久化观察器。
            executionListener.onAssistantResponse(response);
            if (response.content() != null && !response.content().isBlank()) {
                lastContent = response.content();
            }

            // 无工具调用 → 任务结束
            if (!response.hasToolCalls()) {
                stepLogger.accept("step " + step + ": stop (no tool calls)");
                return lastContent == null ? "" : lastContent;
            }

            // 串行执行本轮全部 tool call，再进入下一轮 chat
            for (int callIndex = 0; callIndex < response.toolCalls().size(); callIndex++) {
                ToolCall call = response.toolCalls().get(callIndex);
                // 打印模型传入的参数 JSON，便于对照调试
                stepLogger.accept(
                        "tool: " + call.name()
                                + " id=" + call.id()
                                + " args=" + call.argumentsJson()
                );
                // 工具执行前先记录领取状态，避免崩溃后误把已开始调用视为未执行。
                executionListener.onToolExecutionStarting(call, callIndex);
                ToolResult result = toolRegistry.execute(
                        call.id(),
                        call.name(),
                        call.argumentsJson(),
                        toolContext
                );
                // observation 必须在回灌模型前同步落库，确保恢复历史完整。
                executionListener.onToolExecutionCompleted(call, callIndex, result);
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
