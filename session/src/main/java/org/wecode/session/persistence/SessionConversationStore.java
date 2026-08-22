package org.wecode.session.persistence;

import org.apache.ibatis.session.SqlSession;
import org.wecode.id.IdGenerator;
import org.wecode.id.UuidV7IdGenerator;
import org.wecode.llm.model.LlmResponse;
import org.wecode.llm.model.Message;
import org.wecode.llm.model.ToolCall;
import org.wecode.session.Session;
import org.wecode.session.SessionId;
import org.wecode.session.SessionTitle;
import org.wecode.session.persistence.entity.SessionMessageRecord;
import org.wecode.session.persistence.entity.SessionRecord;
import org.wecode.session.persistence.entity.SessionStatus;
import org.wecode.session.persistence.entity.SessionTitleSource;
import org.wecode.session.persistence.entity.ToolExecutionRecord;
import org.wecode.session.persistence.entity.ToolExecutionStatus;
import org.wecode.session.persistence.entity.WorkspaceRecord;
import org.wecode.session.persistence.mapper.SessionMessagePersistenceMapper;
import org.wecode.session.persistence.mapper.SessionPersistenceMapper;
import org.wecode.session.persistence.mapper.ToolExecutionPersistenceMapper;
import org.wecode.session.persistence.payload.AssistantPayload;
import org.wecode.session.persistence.payload.SessionMessagePayload;
import org.wecode.session.persistence.payload.SessionPayloadCodec;
import org.wecode.session.persistence.payload.SystemPayload;
import org.wecode.session.persistence.payload.ToolExecutionResultPayload;
import org.wecode.session.persistence.payload.UserPayload;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 会话创建、消息追加、标题更新和 Agent 工具事件的事务性持久化入口。
 * <p>
 * 本类只处理数据库状态，不调用模型或执行工具，避免会话模块反向依赖 CLI、Agent 或工具模块。
 */
public final class SessionConversationStore {

    /** 单次工具运行的短期租约，当前单进程 MVP 仅用于保留可恢复状态。 */
    private static final long TOOL_LEASE_MILLIS = 5 * 60 * 1_000L;

    private final SessionDatabase database;
    private final IdGenerator idGenerator;
    private final SessionPayloadCodec payloadCodec;
    private final Clock clock;

    /**
     * 使用生产默认依赖创建会话持久化入口。
     *
     * @param database 已完成 Flyway 迁移的会话数据库
     */
    public SessionConversationStore(SessionDatabase database) {
        this(database, new UuidV7IdGenerator(), new SessionPayloadCodec(), Clock.systemUTC());
    }

    /**
     * 使用可注入的 ID、编码器和时钟创建入口，供受控测试使用。
     *
     * @param database     已完成 Flyway 迁移的会话数据库
     * @param idGenerator  会话、消息和工具执行记录 ID 生成器
     * @param payloadCodec 历史载荷编解码器
     * @param clock        UTC 时间源
     */
    public SessionConversationStore(
            SessionDatabase database,
            IdGenerator idGenerator,
            SessionPayloadCodec payloadCodec,
            Clock clock
    ) {
        this.database = Objects.requireNonNull(database, "database");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.payloadCodec = Objects.requireNonNull(payloadCodec, "payloadCodec");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 使用首条用户消息创建会话，并在同一事务中写入 system 与 user 历史。
     *
     * @param workspace        已确认且已持久化的工作区
     * @param systemPrompt     本次会话固定使用的 system prompt
     * @param promptVersion    system prompt 规则版本
     * @param firstUserMessage 首条用户消息原文
     * @param temporaryTitle   从首条用户消息生成的临时标题
     * @return 已持久化的会话元数据与对应内存消息历史
     */
    public CreatedSession createFromFirstUserMessage(
            WorkspaceRecord workspace,
            String systemPrompt,
            String promptVersion,
            String firstUserMessage,
            String temporaryTitle
    ) {
        Objects.requireNonNull(workspace, "workspace");
        SessionTitle.requireValid(temporaryTitle);
        UserPayload userPayload = new UserPayload(firstUserMessage);
        SystemPayload systemPayload = new SystemPayload(systemPrompt, promptVersion);
        long now = now();
        SessionRecord created = new SessionRecord(
                SessionId.create(idGenerator).value(),
                workspace.id(),
                ".",
                temporaryTitle,
                SessionTitleSource.TEMPORARY,
                SessionStatus.IDLE,
                0,
                0,
                now,
                now,
                "{\"formatVersion\":1}"
        );

        try (SqlSession sqlSession = database.openSession()) {
            SessionPersistenceMapper sessionMapper = sqlSession.getMapper(SessionPersistenceMapper.class);
            SessionMessagePersistenceMapper messageMapper = sqlSession.getMapper(SessionMessagePersistenceMapper.class);
            // 会话根记录必须先于任何历史事件落库，保证外键和序号从零开始建立。
            requireExactlyOne(sessionMapper.insert(created), "insert session");
            SessionRecord afterSystem = appendMessage(
                    sessionMapper,
                    messageMapper,
                    created,
                    systemPayload,
                    SessionStatus.RUNNING
            );
            SessionRecord afterUser = appendMessage(
                    sessionMapper,
                    messageMapper,
                    afterSystem,
                    userPayload,
                    SessionStatus.RUNNING
            );
            sqlSession.commit();

            Session runtimeSession = new Session();
            runtimeSession.append(systemPayload.toMessage());
            runtimeSession.append(userPayload.toMessage());
            return new CreatedSession(afterUser, runtimeSession);
        }
    }

    /**
     * 为当前活动会话持久化一条用户消息，并将会话置为运行中。
     *
     * @param sessionId 会话标识
     * @param content   用户输入原文
     */
    public void appendUserMessage(String sessionId, String content) {
        UserPayload payload = new UserPayload(content);
        try (SqlSession sqlSession = database.openSession()) {
            SessionPersistenceMapper sessionMapper = sqlSession.getMapper(SessionPersistenceMapper.class);
            SessionMessagePersistenceMapper messageMapper = sqlSession.getMapper(SessionMessagePersistenceMapper.class);
            SessionRecord current = requireSession(sessionMapper, sessionId);
            appendMessage(sessionMapper, messageMapper, current, payload, SessionStatus.RUNNING);
            sqlSession.commit();
        }
    }

    /**
     * 用户手动重命名会话；标题一旦写入即标记为 {@code USER}。
     *
     * @param sessionId 会话标识
     * @param title     已解析出的 rename 参数
     */
    public void renameTitle(String sessionId, String title) {
        SessionTitle.requireValid(title);
        try (SqlSession sqlSession = database.openSession()) {
            SessionPersistenceMapper sessionMapper = sqlSession.getMapper(SessionPersistenceMapper.class);
            SessionRecord current = requireSession(sessionMapper, sessionId);
            requireExactlyOne(
                    sessionMapper.renameTitle(sessionId, current.version(), title, now()),
                    "rename session title"
            );
            sqlSession.commit();
        }
    }

    /**
     * 使用模型结果替换临时标题；用户已重命名或标题已生成时不覆盖。
     *
     * @param sessionId 会话标识
     * @param title     已校验的模型标题
     * @return 实际替换时返回 {@code true}
     */
    public boolean replaceTemporaryTitle(String sessionId, String title) {
        SessionTitle.requireValid(title);
        try (SqlSession sqlSession = database.openSession()) {
            SessionPersistenceMapper sessionMapper = sqlSession.getMapper(SessionPersistenceMapper.class);
            SessionRecord current = requireSession(sessionMapper, sessionId);
            int changed = sessionMapper.replaceTemporaryTitle(sessionId, current.version(), title, now());
            // 条件不满足代表已被用户或先前模型结果命名，不是异常。
            if (changed == 1) {
                sqlSession.commit();
                return true;
            }
            sqlSession.rollback();
            return false;
        }
    }

    /**
     * 持久化一条模型 assistant 响应，并预先创建本轮的待执行工具记录。
     *
     * @param sessionId 会话标识
     * @param response  本轮完整模型响应
     * @return assistant 消息与按调用顺序创建的工具执行记录
     */
    public PersistedAssistant appendAssistantResponse(String sessionId, LlmResponse response) {
        Objects.requireNonNull(response, "response");
        AssistantPayload payload = AssistantPayload.fromResponse(response);
        // 在写库前验证 assistant 正文、停止原因和工具调用组合可被完整恢复。
        payload.toMessage(response.toolCalls());
        try (SqlSession sqlSession = database.openSession()) {
            SessionPersistenceMapper sessionMapper = sqlSession.getMapper(SessionPersistenceMapper.class);
            SessionMessagePersistenceMapper messageMapper = sqlSession.getMapper(SessionMessagePersistenceMapper.class);
            ToolExecutionPersistenceMapper toolMapper = sqlSession.getMapper(ToolExecutionPersistenceMapper.class);
            SessionRecord current = requireSession(sessionMapper, sessionId);
            SessionStatus nextStatus = response.hasToolCalls() ? SessionStatus.RUNNING : SessionStatus.IDLE;
            AppendedMessage appended = appendMessageWithRecord(
                    sessionMapper,
                    messageMapper,
                    current,
                    payload,
                    nextStatus
            );
            List<ToolExecutionRecord> executions = new ArrayList<>();
            for (int index = 0; index < response.toolCalls().size(); index++) {
                ToolCall call = response.toolCalls().get(index);
                ToolExecutionRecord execution = new ToolExecutionRecord(
                        idGenerator.nextId(),
                        appended.message().id(),
                        call.id(),
                        index,
                        call.name(),
                        call.argumentsJson(),
                        ToolExecutionStatus.PENDING,
                        null,
                        null,
                        0,
                        null,
                        null,
                        0,
                        appended.message().createdAt(),
                        null,
                        null,
                        appended.message().createdAt()
                );
                requireExactlyOne(toolMapper.insert(execution), "insert tool execution");
                executions.add(execution);
            }
            sqlSession.commit();
            return new PersistedAssistant(appended.message().id(), List.copyOf(executions));
        }
    }

    /**
     * 在真实执行工具前领取其记录，避免崩溃后把已开始的调用重新当作 pending。
     *
     * @param execution 已持久化的 pending 工具记录
     * @return 持有租约、版本已递增的工具记录
     */
    public ToolExecutionRecord claimToolExecution(ToolExecutionRecord execution) {
        Objects.requireNonNull(execution, "execution");
        long now = now();
        String leaseToken = idGenerator.nextId();
        try (SqlSession sqlSession = database.openSession()) {
            ToolExecutionPersistenceMapper toolMapper = sqlSession.getMapper(ToolExecutionPersistenceMapper.class);
            requireExactlyOne(
                    toolMapper.claimPending(
                            execution.id(),
                            execution.revision(),
                            leaseToken,
                            now + TOOL_LEASE_MILLIS,
                            now,
                            now
                    ),
                    "claim tool execution"
            );
            sqlSession.commit();
            return new ToolExecutionRecord(
                    execution.id(), execution.assistantMessageId(), execution.callId(), execution.callIndex(),
                    execution.toolName(), execution.argumentsJson(), ToolExecutionStatus.RUNNING,
                    null, null, execution.attemptCount() + 1, leaseToken, now + TOOL_LEASE_MILLIS,
                    execution.revision() + 1, execution.createdAt(), now, null, now
            );
        }
    }

    /**
     * 持久化工具执行结果，使下一轮模型请求可从历史中恢复 observation。
     *
     * @param execution 已被本进程领取的工具记录
     * @param result    工具返回的 observation 文本
     * @param failed    工具是否以可恢复错误 observation 结束
     */
    public void completeToolExecution(ToolExecutionRecord execution, String result, boolean failed) {
        Objects.requireNonNull(execution, "execution");
        ToolExecutionResultPayload payload = new ToolExecutionResultPayload(result);
        SessionPayloadCodec.EncodedPayload encoded = payloadCodec.encodeToolResult(payload);
        long now = now();
        try (SqlSession sqlSession = database.openSession()) {
            ToolExecutionPersistenceMapper toolMapper = sqlSession.getMapper(ToolExecutionPersistenceMapper.class);
            requireExactlyOne(
                    toolMapper.completeRunning(
                            execution.id(),
                            execution.revision(),
                            execution.leaseToken(),
                            failed ? ToolExecutionStatus.FAILED : ToolExecutionStatus.SUCCEEDED,
                            encoded.payloadJson(),
                            encoded.payloadVersion(),
                            now,
                            now
                    ),
                    "complete tool execution"
            );
            sqlSession.commit();
        }
    }

    /**
     * 将一次正常结束的 Agent 运行恢复到可继续交互的空闲状态。
     *
     * @param sessionId 会话标识
     */
    public void markRunIdle(String sessionId) {
        updateRunStatus(sessionId, SessionStatus.IDLE);
    }

    /**
     * 将异常结束的 Agent 运行标记为中断，供后续恢复策略识别。
     *
     * @param sessionId 会话标识
     */
    public void markRunInterrupted(String sessionId) {
        updateRunStatus(sessionId, SessionStatus.INTERRUPTED);
    }

    /** 以乐观锁更新运行状态。 */
    private void updateRunStatus(String sessionId, SessionStatus status) {
        try (SqlSession sqlSession = database.openSession()) {
            SessionPersistenceMapper sessionMapper = sqlSession.getMapper(SessionPersistenceMapper.class);
            SessionRecord current = requireSession(sessionMapper, sessionId);
            // 已经是目标状态时不重复递增 version。
            if (current.status() == status) {
                return;
            }
            requireExactlyOne(
                    sessionMapper.updateStatus(sessionId, current.version(), status, now()),
                    "update session status"
            );
            sqlSession.commit();
        }
    }

    /** 在同一事务中推进序号并写入一条不可变历史事件。 */
    private SessionRecord appendMessage(
            SessionPersistenceMapper sessionMapper,
            SessionMessagePersistenceMapper messageMapper,
            SessionRecord current,
            SessionMessagePayload payload,
            SessionStatus nextStatus
    ) {
        return appendMessageWithRecord(sessionMapper, messageMapper, current, payload, nextStatus).session();
    }

    /** 在同一事务中推进序号、写入历史事件，并返回新会话元数据。 */
    private AppendedMessage appendMessageWithRecord(
            SessionPersistenceMapper sessionMapper,
            SessionMessagePersistenceMapper messageMapper,
            SessionRecord current,
            SessionMessagePayload payload,
            SessionStatus nextStatus
    ) {
        long createdAt = now();
        long newSequenceNo = current.lastSequenceNo() + 1;
        SessionPayloadCodec.EncodedPayload encoded = payloadCodec.encodeMessage(payload);
        SessionMessageRecord message = new SessionMessageRecord(
                idGenerator.nextId(),
                current.id(),
                newSequenceNo,
                payload.messageType(),
                encoded.payloadVersion(),
                encoded.payloadJson(),
                null,
                createdAt
        );
        requireExactlyOne(
                sessionMapper.advanceForMessageAppend(
                        current.id(),
                        current.version(),
                        current.lastSequenceNo(),
                        newSequenceNo,
                        nextStatus,
                        createdAt
                ),
                "advance session for message append"
        );
        requireExactlyOne(messageMapper.insert(message), "insert session message");
        SessionRecord updated = new SessionRecord(
                current.id(), current.workspaceId(), current.workingDirectoryRelativePath(),
                current.title(), current.titleSource(), nextStatus, newSequenceNo,
                current.version() + 1, current.createdAt(), createdAt, current.metadataJson()
        );
        return new AppendedMessage(updated, message);
    }

    /** 读取必须存在的会话，禁止静默向不存在会话写入数据。 */
    private static SessionRecord requireSession(SessionPersistenceMapper mapper, String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        SessionRecord session = mapper.findById(sessionId);
        if (session == null) {
            throw new IllegalArgumentException("session does not exist: " + sessionId);
        }
        return session;
    }

    /** 检查单行写入契约，避免把部分更新误判为成功。 */
    private static void requireExactlyOne(int changedRows, String operation) {
        if (changedRows != 1) {
            throw new IllegalStateException(operation + " must affect exactly one row: " + changedRows);
        }
    }

    /** 返回当前 UTC epoch milliseconds。 */
    private long now() {
        return clock.millis();
    }

    /** 首条用户消息创建完成后的持久化状态与运行时消息历史。 */
    public record CreatedSession(SessionRecord session, Session runtimeSession) {

        /** 校验创建结果完整可供 Agent 继续使用。 */
        public CreatedSession {
            Objects.requireNonNull(session, "session");
            Objects.requireNonNull(runtimeSession, "runtimeSession");
        }
    }

    /** 已落库 assistant 消息及其按模型原始顺序创建的工具执行记录。 */
    public record PersistedAssistant(String messageId, List<ToolExecutionRecord> executions) {

        /** 校验 assistant 持久化结果。 */
        public PersistedAssistant {
            Objects.requireNonNull(messageId, "messageId");
            executions = List.copyOf(Objects.requireNonNull(executions, "executions"));
        }
    }

    /** 追加消息后需要同时保留的新会话版本和消息记录。 */
    private record AppendedMessage(SessionRecord session, SessionMessageRecord message) {
    }
}
