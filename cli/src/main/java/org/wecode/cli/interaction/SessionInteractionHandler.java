package org.wecode.cli.interaction;

import org.wecode.agent.AgentExecutionListener;
import org.wecode.agent.AgentLoop;
import org.wecode.agent.PromptBuilder;
import org.wecode.llm.chat.ChatModel;
import org.wecode.llm.model.LlmResponse;
import org.wecode.llm.model.ToolCall;
import org.wecode.session.SessionTitle;
import org.wecode.session.persistence.SessionConversationStore;
import org.wecode.session.persistence.entity.ToolExecutionRecord;
import org.wecode.session.persistence.entity.WorkspaceRecord;
import org.wecode.tools.registry.ToolRegistry;
import org.wecode.tools.result.ToolResult;
import org.wecode.tools.spi.ToolContext;

import java.util.List;
import java.util.Objects;

/**
 * 当前终端进程的活动 session 编排：首条消息创建会话，后续消息持续追加到同一会话。
 */
public final class SessionInteractionHandler implements InteractionHandler {

    /** 持久化 system prompt 的当前规则版本。 */
    private static final String PROMPT_VERSION = "v1";

    private final SessionConversationStore conversationStore;
    private final WorkspaceRecord workspace;
    private final String systemPrompt;
    private final TitleGenerator titleGenerator;
    private final ChatModel chatModel;
    private final ToolRegistry toolRegistry;
    private final ToolContext toolContext;

    private String activeSessionId;

    /**
     * @param conversationStore session 消息、标题和工具事件持久化入口
     * @param workspace         当前已确认的工作区
     * @param titleGenerator    一次性标题生成器
     * @param chatModel         当前激活 Provider 的模型
     * @param toolRegistry      本轮 Agent 可调用的工具
     * @param toolContext       当前工作区工具访问边界
     */
    public SessionInteractionHandler(
            SessionConversationStore conversationStore,
            WorkspaceRecord workspace,
            TitleGenerator titleGenerator,
            ChatModel chatModel,
            ToolRegistry toolRegistry,
            ToolContext toolContext
    ) {
        this.conversationStore = Objects.requireNonNull(conversationStore, "conversationStore");
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.systemPrompt = new PromptBuilder().buildSystemPrompt();
        this.titleGenerator = Objects.requireNonNull(titleGenerator, "titleGenerator");
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel");
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry");
        this.toolContext = Objects.requireNonNull(toolContext, "toolContext");
    }

    /**
     * 首条自然语言创建 session 并尝试一次模型标题；后续消息只追加并运行 Agent。
     *
     * @param userMessage 用户原始输入
     * @return Agent 最终文本
     */
    @Override
    public String handleUserMessage(String userMessage) {
        Objects.requireNonNull(userMessage, "userMessage");
        // 只有没有活动 session 的第一条用户消息才创建 session 和触发标题生成。
        if (activeSessionId == null) {
            createSessionFromFirstUserMessage(userMessage);
        } else {
            // 已绑定 session 时，后续输入只追加 user 消息，绝不再次生成标题。
            conversationStore.appendUserMessage(activeSessionId, userMessage);
        }
        return runAgentForActiveSession();
    }

    /**
     * 用户手动更新当前活动 session 的标题。
     *
     * @param title rename 命令参数
     */
    @Override
    public void renameActiveSession(String title) {
        // 参数校验先于数据库访问，避免将空白或超长标题带入持久化层。
        SessionTitle.requireValid(title);
        if (activeSessionId == null) {
            throw new IllegalStateException("当前没有活动会话，无法重命名。");
        }
        conversationStore.renameTitle(activeSessionId, title);
    }

    /**
     * @return 当前交互是否已有首条消息创建的 session
     */
    @Override
    public boolean hasActiveSession() {
        return activeSessionId != null;
    }

    /** 使用首条消息创建会话、写入临时标题，并至多尝试一次模型替换。 */
    private void createSessionFromFirstUserMessage(String firstUserMessage) {
        String temporaryTitle = SessionTitle.temporaryFromFirstUserMessage(firstUserMessage);
        SessionConversationStore.CreatedSession created = conversationStore.createFromFirstUserMessage(
                workspace,
                systemPrompt,
                PROMPT_VERSION,
                firstUserMessage,
                temporaryTitle
        );
        activeSessionId = created.session().id();

        try {
            // 本 session 仅在这里调用一次标题模型；任何失败都保留临时标题且不影响 Agent。
            titleGenerator.generate(firstUserMessage)
                    .ifPresent(title -> conversationStore.replaceTemporaryTitle(activeSessionId, title));
        } catch (RuntimeException ignored) {
            // 标题服务是体验增强，不得阻断首轮对话；临时标题已经随创建事务落库。
        }
    }

    /** 运行 Agent，并在 assistant、工具领取、工具结果边界同步持久化。 */
    private String runAgentForActiveSession() {
        try {
            AgentLoop agentLoop = new AgentLoop(
                    chatModel,
                    toolRegistry,
                    toolContext,
                    new PersistingAgentExecutionListener(conversationStore, activeSessionId)
            );
            // 每轮请求均从 SQLite 重建上下文，运行期不再读取内存会话历史。
            String result = agentLoop.run(() -> conversationStore.loadMessagesForLlm(activeSessionId));
            conversationStore.markRunIdle(activeSessionId);
            return result;
        } catch (RuntimeException exception) {
            // Agent 或持久化异常时留下中断标记，禁止伪装成可继续的正常完成态。
            try {
                conversationStore.markRunInterrupted(activeSessionId);
            } catch (RuntimeException statusException) {
                exception.addSuppressed(statusException);
            }
            throw exception;
        }
    }

    /**
     * 将 AgentLoop 的执行边界映射为 session 数据库中的 assistant 和工具执行记录。
     */
    private static final class PersistingAgentExecutionListener implements AgentExecutionListener {

        private final SessionConversationStore conversationStore;
        private final String sessionId;
        private List<ToolExecutionRecord> pendingExecutions = List.of();
        private java.util.ArrayList<ToolExecutionRecord> claimedExecutions = new java.util.ArrayList<>();

        /**
         * @param conversationStore 会话持久化入口
         * @param sessionId         当前活动会话标识
         */
        private PersistingAgentExecutionListener(SessionConversationStore conversationStore, String sessionId) {
            this.conversationStore = conversationStore;
            this.sessionId = sessionId;
        }

        /** assistant 落库时同时创建本轮所有 pending 工具记录。 */
        @Override
        public void onAssistantResponse(LlmResponse response) {
            SessionConversationStore.PersistedAssistant persisted = conversationStore.appendAssistantResponse(
                    sessionId,
                    response
            );
            pendingExecutions = persisted.executions();
            claimedExecutions = new java.util.ArrayList<>(pendingExecutions);
        }

        /** 实际工具调用前先领取相同下标的 pending 记录。 */
        @Override
        public void onToolExecutionStarting(ToolCall call, int callIndex) {
            ToolExecutionRecord pending = executionAt(pendingExecutions, call, callIndex, "pending");
            claimedExecutions.set(
                    callIndex,
                    conversationStore.claimToolExecution(pending)
            );
        }

        /** 工具结果产生后，使用领取记录的租约持久化 observation。 */
        @Override
        public void onToolExecutionCompleted(ToolCall call, int callIndex, ToolResult result) {
            ToolExecutionRecord claimed = executionAt(claimedExecutions, call, callIndex, "claimed");
            conversationStore.completeToolExecution(claimed, result.content(), result.error());
        }

        /** 校验 AgentLoop 与持久化工具记录的调用顺序完全一致。 */
        private static ToolExecutionRecord executionAt(
                List<ToolExecutionRecord> executions,
                ToolCall call,
                int callIndex,
                String state
        ) {
            if (callIndex < 0 || callIndex >= executions.size()) {
                throw new IllegalStateException("Missing " + state + " tool execution at index " + callIndex);
            }
            ToolExecutionRecord execution = executions.get(callIndex);
            if (!execution.callId().equals(call.id())) {
                throw new IllegalStateException("Tool execution call id does not match persisted assistant response");
            }
            return execution;
        }
    }
}
