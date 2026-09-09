package org.wecode.session.persistence;

import org.apache.ibatis.session.SqlSession;
import org.wecode.id.IdGenerator;
import org.wecode.id.UuidV7IdGenerator;
import org.wecode.llm.model.LlmResponse;
import org.wecode.llm.model.Message;
import org.wecode.llm.model.ToolCall;
import org.wecode.session.SessionId;
import org.wecode.session.SessionTitle;
import org.wecode.session.persistence.entity.SessionMessageRecord;
import org.wecode.session.persistence.entity.SessionMessageType;
import org.wecode.session.persistence.entity.SessionRecord;
import org.wecode.session.persistence.entity.SessionStatus;
import org.wecode.session.persistence.entity.SessionTitleSource;
import org.wecode.session.persistence.entity.ToolExecutionRecord;
import org.wecode.session.persistence.entity.ToolExecutionStatus;
import org.wecode.session.persistence.entity.WorkspaceRecord;
import org.wecode.session.persistence.compaction.CompactionCommitResult;
import org.wecode.session.persistence.compaction.CompactionSnapshot;
import org.wecode.session.persistence.compaction.CompactionTranscript;
import org.wecode.session.persistence.mapper.SessionMessagePersistenceMapper;
import org.wecode.session.persistence.mapper.SessionPersistenceMapper;
import org.wecode.session.persistence.mapper.ToolExecutionPersistenceMapper;
import org.wecode.session.persistence.payload.AssistantPayload;
import org.wecode.session.persistence.payload.CompactionPayload;
import org.wecode.session.persistence.payload.SessionMessagePayload;
import org.wecode.session.persistence.payload.SessionPayloadCodec;
import org.wecode.session.persistence.payload.SystemPayload;
import org.wecode.session.persistence.payload.ToolExecutionResultPayload;
import org.wecode.session.persistence.payload.UserPayload;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
     * @return 已持久化的会话元数据
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

            return new CreatedSession(afterUser);
        }
    }

    /**
     * 从 SQLite 重建当前请求应发送给 LLM 的消息数组。
     * <p>
     * 始终保留会话最初持久化的 system prompt；存在 compaction 时，再注入最后一个
     * compaction 摘要，并回放其覆盖边界之后的未压缩历史。消息顺序仅由 sequenceNo 决定。
     *
     * @param sessionId 会话标识
     * @return 可直接传递给 {@code ChatModel} 的有序消息列表
     */
    public List<Message> loadMessagesForLlm(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        try (SqlSession sqlSession = database.openSession()) {
            SessionPersistenceMapper sessionMapper = sqlSession.getMapper(SessionPersistenceMapper.class);
            SessionMessagePersistenceMapper messageMapper = sqlSession.getMapper(SessionMessagePersistenceMapper.class);
            ToolExecutionPersistenceMapper toolMapper = sqlSession.getMapper(ToolExecutionPersistenceMapper.class);
            // 会话不存在时禁止返回空上下文，避免调用方误把错误会话当成新会话。
            requireSession(sessionMapper, sessionId);
            // 找到最后一个compaction节点，作为历史回放的边界；不存在时回放完整历史。
            SessionMessageRecord latestCompaction = messageMapper.findLatestCompaction(sessionId);
            List<Message> messages = new ArrayList<>();
            long afterSequence = 0L;
            List<SessionMessageRecord> history;

            // 存在压缩节点时，固定规则和摘要必须先于未压缩尾部进入模型上下文。
            if (latestCompaction != null) {
                SessionMessageRecord initialSystem = requireInitialSystemMessage(
                        messageMapper.findFirstSystemMessage(sessionId),
                        sessionId
                );
                messages.add(toSystemMessage(initialSystem));
                messages.add(toCompactionMessage(latestCompaction));
                afterSequence = latestCompaction.compactsThroughSequence();
                history = messageMapper.findAfterSequenceExcludingCompaction(sessionId, afterSequence);
            } else {
                // 没有压缩节点时，完整历史即为当前模型上下文。
                history = messageMapper.findAllBySessionId(sessionId);
            }

            Map<String, List<ToolExecutionRecord>> executionsByAssistantMessage = groupExecutionsByAssistantMessage(
                    toolMapper.findBySessionIdAfterSequence(sessionId, afterSequence)
            );
            // 按会话序号将持久化事件恢复为 OpenAI 兼容的 role 消息数组。
            for (SessionMessageRecord record : history) {
                appendContextMessage(messages, record, executionsByAssistantMessage);
            }
            return List.copyOf(messages);
        }
    }

    /**
     * 读取供摘要模型使用的结构化原始历史。
     * <p>
     * 固定 system prompt 不属于可压缩历史。存在既有检查点时，旧摘要单独返回，且仅读取其覆盖边界
     * 之后的原始 USER、ASSISTANT 与工具执行事实。
     *
     * @param sessionId 会话标识
     * @return 按会话顺序构造的摘要源；不产生数据库写入
     */
    public CompactionTranscript loadCompactionTranscript(String sessionId) {
        return loadCompactionSnapshot(sessionId).transcript();
    }

    /**
     * 读取供一次摘要生成和后续条件提交使用的稳定会话快照。
     * <p>
     * 此方法只在短暂的只读数据库会话中读取会话版本、序号和摘要源；返回后数据库会话已关闭，
     * 调用方必须在该方法返回后才调用模型，避免跨模型调用持有 SQLite 事务。
     *
     * @param sessionId 会话标识
     * @return 包含乐观锁条件、压缩边界和结构化历史的稳定快照
     */
    public CompactionSnapshot loadCompactionSnapshot(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        try (SqlSession sqlSession = database.openSession()) {
            SessionPersistenceMapper sessionMapper = sqlSession.getMapper(SessionPersistenceMapper.class);
            SessionMessagePersistenceMapper messageMapper = sqlSession.getMapper(SessionMessagePersistenceMapper.class);
            ToolExecutionPersistenceMapper toolMapper = sqlSession.getMapper(ToolExecutionPersistenceMapper.class);
            // 会话不存在时拒绝构造空摘要源，避免调用方误判为无历史。
            SessionRecord current = requireSession(sessionMapper, sessionId);
            SessionMessageRecord latestCompaction = messageMapper.findLatestCompaction(sessionId);
            long afterSequence = 0L;
            String previousSummary = null;
            List<SessionMessageRecord> history;

            if (latestCompaction != null) {
                CompactionPayload payload = requireCompactionPayload(latestCompaction);
                previousSummary = payload.renderedMemory();
                afterSequence = latestCompaction.compactsThroughSequence();
                history = messageMapper.findAfterSequenceExcludingCompaction(sessionId, afterSequence);
            } else {
                // 首次压缩时从完整原始会话中筛除固定 system prompt。
                history = messageMapper.findAllBySessionId(sessionId);
            }

            Map<String, List<ToolExecutionRecord>> executionsByAssistantMessage = groupExecutionsByAssistantMessage(
                    toolMapper.findBySessionIdAfterSequence(sessionId, afterSequence)
            );
            List<CompactionTranscript.Entry> entries = new ArrayList<>();
            for (SessionMessageRecord record : history) {
                appendCompactionTranscriptEntry(entries, record, executionsByAssistantMessage);
            }
            CompactionTranscript transcript = new CompactionTranscript(previousSummary, entries);
            // 第一期不保留未压缩尾部，因此检查点覆盖读取快照时的全部既有事件。
            return new CompactionSnapshot(
                    current.id(),
                    current.version(),
                    current.lastSequenceNo(),
                    current.lastSequenceNo(),
                    current.status(),
                    transcript
            );
        }
    }

    /**
     * 仅当会话仍保持读取快照时的版本与最后序号时，原子追加一条压缩检查点。
     * <p>
     * 条件不命中时不会写入任何记录，并返回过期快照；摘要模型调用不属于本方法，故该事务不会跨越模型调用。
     *
     * @param snapshot        摘要生成前读取的稳定快照
     * @param summaryContent  已通过调用方校验的摘要正文
     * @param strategyVersion 生成摘要时使用的策略版本
     * @return 已提交的压缩边界，或表示未写入的过期快照结果
     */
    public CompactionCommitResult appendCompactionIfUnchanged(
            CompactionSnapshot snapshot,
            String summaryContent,
            String strategyVersion
    ) {
        snapshot = Objects.requireNonNull(snapshot, "snapshot");
        // 没有早于检查点的历史事件时，数据库约束不允许创建 compaction。
        if (snapshot.compactsThroughSequence() <= 0) {
            throw new IllegalArgumentException("snapshot must cover at least one historical event");
        }
        CompactionPayload payload = new CompactionPayload(summaryContent, strategyVersion);
        SessionPayloadCodec.EncodedPayload encoded = payloadCodec.encodeMessage(payload);
        long createdAt = now();
        long newSequenceNo = Math.addExact(snapshot.expectedLastSequenceNo(), 1L);
        SessionMessageRecord compaction = new SessionMessageRecord(
                idGenerator.nextId(),
                snapshot.sessionId(),
                newSequenceNo,
                SessionMessageType.COMPACTION,
                encoded.payloadVersion(),
                encoded.payloadJson(),
                snapshot.compactsThroughSequence(),
                createdAt
        );

        try (SqlSession sqlSession = database.openSession()) {
            try {
                SessionPersistenceMapper sessionMapper = sqlSession.getMapper(SessionPersistenceMapper.class);
                SessionMessagePersistenceMapper messageMapper = sqlSession.getMapper(SessionMessagePersistenceMapper.class);
                // 同时比较版本和最后序号，任一历史或元数据变化都会使本次旧摘要失效。
                int advanced = sessionMapper.advanceForMessageAppend(
                        snapshot.sessionId(),
                        snapshot.expectedSessionVersion(),
                        snapshot.expectedLastSequenceNo(),
                        newSequenceNo,
                        snapshot.sessionStatus(),
                        createdAt
                );
                if (advanced == 0) {
                    // 条件更新未命中时禁止插入检查点，避免旧摘要覆盖新历史。
                    sqlSession.rollback();
                    return new CompactionCommitResult.StaleSnapshot();
                }
                requireExactlyOne(messageMapper.insert(compaction), "insert compaction message");
                sqlSession.commit();
                return new CompactionCommitResult.Committed(snapshot.compactsThroughSequence());
            } catch (RuntimeException exception) {
                // 插入或编码相关异常时回滚已推进的会话序号，保持两张表原子一致。
                sqlSession.rollback();
                throw exception;
            }
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

    /** 将一条持久化事件追加为模型上下文；ASSISTANT 后紧跟其工具结果。 */
    private void appendContextMessage(
            List<Message> messages,
            SessionMessageRecord record,
            Map<String, List<ToolExecutionRecord>> executionsByAssistantMessage
    ) {
        SessionMessagePayload payload = payloadCodec.decodeMessage(record);
        switch (record.messageType()) {
            case SYSTEM -> messages.add(toSystemMessage(record));
            case USER -> messages.add(((UserPayload) payload).toMessage());
            case ASSISTANT -> appendAssistantContext(
                    messages,
                    (AssistantPayload) payload,
                    executionsByAssistantMessage.getOrDefault(record.id(), List.of())
            );
            // 已选取的最新压缩消息会在历史尾部之前单独注入，其他压缩节点必须被排除。
            case COMPACTION -> throw new IllegalStateException(
                    "Compaction message must not appear in uncompressed LLM history: " + record.id()
            );
        }
    }

    /** 解码并校验会话创建时保存的固定 system prompt。 */
    private Message toSystemMessage(SessionMessageRecord record) {
        SessionMessagePayload payload = payloadCodec.decodeMessage(record);
        if (!(payload instanceof SystemPayload systemPayload)) {
            throw new IllegalStateException("Expected SYSTEM payload: " + record.id());
        }
        return systemPayload.toMessage();
    }

    /** 解码最新 compaction 摘要，并以带固定安全前缀的 assistant 历史上下文回灌。 */
    private Message toCompactionMessage(SessionMessageRecord record) {
        return requireCompactionPayload(record).toContextMessage();
    }

    /** 解码并校验一条 compaction 消息的持久化载荷。 */
    private CompactionPayload requireCompactionPayload(SessionMessageRecord record) {
        SessionMessagePayload payload = payloadCodec.decodeMessage(record);
        if (!(payload instanceof CompactionPayload compactionPayload)) {
            throw new IllegalStateException("Expected COMPACTION payload: " + record.id());
        }
        return compactionPayload;
    }

    /** 将一条持久化历史事件转换为摘要输入条目，固定 system 与旧 compaction 均不参与摘要。 */
    private void appendCompactionTranscriptEntry(
            List<CompactionTranscript.Entry> entries,
            SessionMessageRecord record,
            Map<String, List<ToolExecutionRecord>> executionsByAssistantMessage
    ) {
        SessionMessagePayload payload = payloadCodec.decodeMessage(record);
        switch (record.messageType()) {
            case SYSTEM -> {
                // 固定规则会在后续请求中继续注入，不应被模型摘要改写。
            }
            case USER -> entries.add(new CompactionTranscript.UserEntry(
                    record.sequenceNo(),
                    ((UserPayload) payload).content()
            ));
            case ASSISTANT -> {
                AssistantPayload assistantPayload = (AssistantPayload) payload;
                List<ToolExecutionRecord> executions = executionsByAssistantMessage.getOrDefault(record.id(), List.of());
                List<ToolCall> toolCalls = executions.stream()
                        .map(execution -> new ToolCall(
                                execution.callId(),
                                execution.toolName(),
                                execution.argumentsJson()
                        ))
                        .toList();
                // 复用正常上下文恢复时的工具协议校验，防止损坏历史绕过摘要路径。
                assistantPayload.toMessage(toolCalls);
                entries.add(new CompactionTranscript.AssistantEntry(
                        record.sequenceNo(),
                        assistantPayload.content(),
                        assistantPayload.reasoningContent(),
                        toCompactionToolExecutions(executions)
                ));
            }
            case COMPACTION -> throw new IllegalStateException(
                    "Compaction message must not appear in compaction transcript history: " + record.id()
            );
        }
    }

    /** 将终态工具执行记录转换为带成功/失败事实的摘要源条目。 */
    private List<CompactionTranscript.ToolExecutionEntry> toCompactionToolExecutions(
            List<ToolExecutionRecord> executions
    ) {
        List<CompactionTranscript.ToolExecutionEntry> entries = new ArrayList<>();
        for (ToolExecutionRecord execution : executions) {
            // 未完成或副作用不确定的调用不能进入摘要，否则会把猜测固化为历史事实。
            if (execution.status() != ToolExecutionStatus.SUCCEEDED
                    && execution.status() != ToolExecutionStatus.FAILED) {
                throw new IllegalStateException(
                        "Cannot build compaction transcript from incomplete tool execution: "
                                + execution.id() + " status=" + execution.status()
                );
            }
            if (execution.resultJson() == null || execution.resultPayloadVersion() == null) {
                throw new IllegalStateException(
                        "Completed tool execution is missing persisted result: " + execution.id()
                );
            }
            ToolExecutionResultPayload result = payloadCodec.decodeToolResult(
                    execution.resultPayloadVersion(),
                    execution.resultJson()
            );
            entries.add(new CompactionTranscript.ToolExecutionEntry(
                    execution.callId(),
                    execution.toolName(),
                    execution.argumentsJson(),
                    execution.status(),
                    result.content()
            ));
        }
        return List.copyOf(entries);
    }

    /** 还原 assistant 调用及其已完成的工具 observation，确保 LLM 工具协议完整。 */
    private void appendAssistantContext(
            List<Message> messages,
            AssistantPayload payload,
            List<ToolExecutionRecord> executions
    ) {
        List<ToolCall> toolCalls = executions.stream()
                .map(execution -> new ToolCall(
                        execution.callId(),
                        execution.toolName(),
                        execution.argumentsJson()
                ))
                .toList();
        messages.add(payload.toMessage(toolCalls));

        for (ToolExecutionRecord execution : executions) {
            // 未完成或副作用不确定的工具调用不能伪造为可继续的模型上下文。
            if (execution.status() != ToolExecutionStatus.SUCCEEDED
                    && execution.status() != ToolExecutionStatus.FAILED) {
                throw new IllegalStateException(
                        "Cannot build LLM context from incomplete tool execution: "
                                + execution.id() + " status=" + execution.status()
                );
            }
            if (execution.resultJson() == null || execution.resultPayloadVersion() == null) {
                throw new IllegalStateException(
                        "Completed tool execution is missing persisted result: " + execution.id()
                );
            }
            ToolExecutionResultPayload result = payloadCodec.decodeToolResult(
                    execution.resultPayloadVersion(),
                    execution.resultJson()
            );
            messages.add(result.toMessage(execution.callId()));
        }
    }

    /** 按 assistant 消息分组批量读取到的工具记录，保留 SQL 已保证的调用顺序。 */
    private static Map<String, List<ToolExecutionRecord>> groupExecutionsByAssistantMessage(
            List<ToolExecutionRecord> executions
    ) {
        Map<String, List<ToolExecutionRecord>> grouped = new HashMap<>();
        for (ToolExecutionRecord execution : executions) {
            grouped.computeIfAbsent(execution.assistantMessageId(), ignored -> new ArrayList<>()).add(execution);
        }
        return grouped;
    }

    /** 读取并校验压缩场景仍可用的首条固定 system 消息。 */
    private static SessionMessageRecord requireInitialSystemMessage(
            SessionMessageRecord initialSystem,
            String sessionId
    ) {
        if (initialSystem == null) {
            throw new IllegalStateException("session has no initial system message: " + sessionId);
        }
        return initialSystem;
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

    /** 首条用户消息创建完成后的持久化状态。 */
    public record CreatedSession(SessionRecord session) {

        /** 校验创建结果包含已持久化的会话元数据。 */
        public CreatedSession {
            Objects.requireNonNull(session, "session");
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
