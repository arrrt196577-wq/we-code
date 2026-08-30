package org.wecode.session.persistence;

import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wecode.llm.model.FinishReason;
import org.wecode.llm.model.LlmResponse;
import org.wecode.llm.model.Message;
import org.wecode.llm.model.Role;
import org.wecode.llm.model.ToolCall;
import org.wecode.session.SessionTitle;
import org.wecode.session.persistence.entity.SessionMessageRecord;
import org.wecode.session.persistence.entity.SessionMessageType;
import org.wecode.session.persistence.entity.SessionRecord;
import org.wecode.session.persistence.entity.SessionStatus;
import org.wecode.session.persistence.entity.SessionTitleSource;
import org.wecode.session.persistence.entity.WorkspaceRecord;
import org.wecode.session.persistence.entity.WorkspaceType;
import org.wecode.session.persistence.entity.ToolExecutionStatus;
import org.wecode.session.persistence.compaction.CompactionTranscript;
import org.wecode.session.persistence.mapper.SessionMessagePersistenceMapper;
import org.wecode.session.persistence.mapper.SessionPersistenceMapper;
import org.wecode.session.persistence.mapper.WorkspacePersistenceMapper;
import org.wecode.session.persistence.payload.CompactionPayload;
import org.wecode.session.persistence.payload.SessionPayloadCodec;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证首条用户消息创建会话、标题来源保护和后续消息追加的持久化编排。
 */
class SessionConversationStoreTest {

    /** 每个测试使用独立 SQLite 数据库。 */
    @TempDir
    Path temporaryDirectory;

    /**
     * 验证首条消息原子创建 system/user 历史，模型标题只能覆盖临时标题，rename 具有最高优先级。
     */
    @Test
    void createsConversationFromFirstUserMessageAndProtectsManualTitle() {
        SessionDatabase database = SessionDatabase.open(temporaryDirectory.resolve("storage"));
        WorkspaceRecord workspace = insertWorkspace(database);
        SessionConversationStore store = new SessionConversationStore(database);
        String firstMessage = "请分析登录接口偶发超时的根因，并给出修复建议。";
        String temporaryTitle = SessionTitle.temporaryFromFirstUserMessage(firstMessage);

        SessionConversationStore.CreatedSession created = store.createFromFirstUserMessage(
                workspace,
                "system prompt",
                "v1",
                firstMessage,
                temporaryTitle
        );

        assertEquals(2, store.loadMessagesForLlm(created.session().id()).size());
        assertEquals(SessionStatus.RUNNING, created.session().status());
        assertEquals(2, created.session().lastSequenceNo());
        assertEquals(2, created.session().version());
        assertEquals(SessionTitleSource.TEMPORARY, created.session().titleSource());
        assertTrue(store.replaceTemporaryTitle(created.session().id(), "排查登录接口超时"));

        store.renameTitle(created.session().id(), "用户指定标题");
        // 已被用户重命名后，迟到的模型结果不得覆盖。
        assertFalse(store.replaceTemporaryTitle(created.session().id(), "迟到的模型标题"));
        store.appendUserMessage(created.session().id(), "请进一步检查线程池配置。");
        store.markRunIdle(created.session().id());

        try (SqlSession sqlSession = database.openSession()) {
            SessionPersistenceMapper sessionMapper = sqlSession.getMapper(SessionPersistenceMapper.class);
            SessionMessagePersistenceMapper messageMapper = sqlSession.getMapper(SessionMessagePersistenceMapper.class);
            SessionRecord persisted = sessionMapper.findById(created.session().id());
            assertEquals("用户指定标题", persisted.title());
            assertEquals(SessionTitleSource.USER, persisted.titleSource());
            assertEquals(SessionStatus.IDLE, persisted.status());
            assertEquals(3, persisted.lastSequenceNo());
            assertEquals(
                    java.util.List.of(SessionMessageType.SYSTEM, SessionMessageType.USER, SessionMessageType.USER),
                    messageMapper.findAllBySessionId(created.session().id())
                            .stream()
                            .map(message -> message.messageType())
                            .toList()
            );
        }
    }

    /**
     * 验证临时标题在 Unicode 码点边界截断，而不会切断 emoji 代理对。
     */
    @Test
    void temporaryTitleUsesUnicodeCodePointLimit() {
        String message = "a".repeat(59) + "😀" + "后续内容";

        String title = SessionTitle.temporaryFromFirstUserMessage(message);

        assertEquals(60, title.codePointCount(0, title.length()));
        assertTrue(title.endsWith("…"));
    }

    /** 验证模型上下文从 SQLite 重建 assistant 工具调用与按调用顺序排列的工具结果。 */
    @Test
    void loadsPersistedAssistantAndToolResultsForLlm() {
        SessionDatabase database = SessionDatabase.open(temporaryDirectory.resolve("storage"));
        WorkspaceRecord workspace = insertWorkspace(database);
        SessionConversationStore store = new SessionConversationStore(database);
        SessionConversationStore.CreatedSession created = store.createFromFirstUserMessage(
                workspace,
                "固定 system prompt",
                "v1",
                "请读取配置文件。",
                "读取配置文件"
        );
        LlmResponse toolCallingResponse = new LlmResponse(
                null,
                List.of(
                        new ToolCall("call-1", "Read", "{\"path\":\"one.txt\"}"),
                        new ToolCall("call-2", "Read", "{\"path\":\"two.txt\"}")
                ),
                FinishReason.TOOL_CALLS,
                "需要依次读取两个文件。"
        );

        SessionConversationStore.PersistedAssistant persisted = store.appendAssistantResponse(
                created.session().id(),
                toolCallingResponse
        );
        // 每条工具 observation 必须在下一轮模型请求前完成持久化。
        for (int index = 0; index < persisted.executions().size(); index++) {
            var claimed = store.claimToolExecution(persisted.executions().get(index));
            store.completeToolExecution(claimed, "result-" + (index + 1), index == 1);
        }
        store.appendUserMessage(created.session().id(), "请比较这两个文件。");

        List<Message> messages = store.loadMessagesForLlm(created.session().id());

        assertEquals(
                List.of(Role.SYSTEM, Role.USER, Role.ASSISTANT, Role.TOOL, Role.TOOL, Role.USER),
                messages.stream().map(Message::role).toList()
        );
        assertEquals("固定 system prompt", messages.get(0).content());
        assertEquals("请读取配置文件。", messages.get(1).content());
        assertEquals(toolCallingResponse.toolCalls(), messages.get(2).toolCalls());
        assertEquals("需要依次读取两个文件。", messages.get(2).reasoningContent());
        assertEquals("call-1", messages.get(3).toolCallId());
        assertEquals("result-1", messages.get(3).content());
        assertEquals("call-2", messages.get(4).toolCallId());
        assertEquals("result-2", messages.get(4).content());
        assertEquals("请比较这两个文件。", messages.get(5).content());
    }

    /** 验证摘要源保留 assistant reasoning、工具参数、工具结果及其成功/失败状态。 */
    @Test
    void loadsStructuredCompactionTranscript() {
        SessionDatabase database = SessionDatabase.open(temporaryDirectory.resolve("storage"));
        WorkspaceRecord workspace = insertWorkspace(database);
        SessionConversationStore store = new SessionConversationStore(database);
        SessionConversationStore.CreatedSession created = store.createFromFirstUserMessage(
                workspace,
                "固定 system prompt",
                "v1",
                "请读取配置文件。",
                "读取配置文件"
        );
        SessionConversationStore.PersistedAssistant persisted = store.appendAssistantResponse(
                created.session().id(),
                new LlmResponse(
                        "准备读取两个文件。",
                        List.of(
                                new ToolCall("call-1", "Read", "{\"path\":\"one.txt\"}"),
                                new ToolCall("call-2", "Read", "{\"path\":\"two.txt\"}")
                        ),
                        FinishReason.TOOL_CALLS,
                        "需要比较两个文件的配置差异。"
                )
        );
        var firstClaimed = store.claimToolExecution(persisted.executions().get(0));
        store.completeToolExecution(firstClaimed, "第一个文件内容", false);
        var secondClaimed = store.claimToolExecution(persisted.executions().get(1));
        store.completeToolExecution(secondClaimed, "第二个文件读取失败", true);
        store.appendUserMessage(created.session().id(), "请说明差异。 ");

        CompactionTranscript transcript = store.loadCompactionTranscript(created.session().id());

        assertEquals(null, transcript.previousSummary());
        assertEquals(3, transcript.entries().size());
        assertEquals("请读取配置文件。", ((CompactionTranscript.UserEntry) transcript.entries().get(0)).content());
        CompactionTranscript.AssistantEntry assistant =
                (CompactionTranscript.AssistantEntry) transcript.entries().get(1);
        assertEquals("准备读取两个文件。", assistant.content());
        assertEquals("需要比较两个文件的配置差异。", assistant.reasoningContent());
        assertEquals(2, assistant.toolExecutions().size());
        assertEquals(ToolExecutionStatus.SUCCEEDED, assistant.toolExecutions().get(0).status());
        assertEquals("第一个文件内容", assistant.toolExecutions().get(0).resultContent());
        assertEquals(ToolExecutionStatus.FAILED, assistant.toolExecutions().get(1).status());
        assertEquals("第二个文件读取失败", assistant.toolExecutions().get(1).resultContent());
        assertEquals("请说明差异。 ", ((CompactionTranscript.UserEntry) transcript.entries().get(2)).content());
    }

    /** 验证已有 compaction 时保留固定 system、注入摘要并回放未覆盖的历史尾部。 */
    @Test
    void loadsInitialSystemCompactionAndUncompressedTailForLlm() {
        SessionDatabase database = SessionDatabase.open(temporaryDirectory.resolve("storage"));
        WorkspaceRecord workspace = insertWorkspace(database);
        SessionConversationStore store = new SessionConversationStore(database);
        SessionConversationStore.CreatedSession created = store.createFromFirstUserMessage(
                workspace,
                "固定 system prompt",
                "v1",
                "已经完成需求分析。",
                "完成需求分析"
        );

        appendCompaction(database, created.session().id(), "历史摘要：需求分析已经完成。");
        store.appendUserMessage(created.session().id(), "请开始编码。");

        List<Message> messages = store.loadMessagesForLlm(created.session().id());

        assertEquals(List.of(Role.SYSTEM, Role.SYSTEM, Role.USER), messages.stream().map(Message::role).toList());
        assertEquals("固定 system prompt", messages.get(0).content());
        assertEquals("历史摘要：需求分析已经完成。", messages.get(1).content());
        assertEquals("请开始编码。", messages.get(2).content());
    }

    /** 验证既有 compaction 的摘要单独作为旧锚点返回，尾部历史不混入旧检查点事件。 */
    @Test
    void loadsPreviousSummaryAndUncompressedTailForCompactionTranscript() {
        SessionDatabase database = SessionDatabase.open(temporaryDirectory.resolve("storage"));
        WorkspaceRecord workspace = insertWorkspace(database);
        SessionConversationStore store = new SessionConversationStore(database);
        SessionConversationStore.CreatedSession created = store.createFromFirstUserMessage(
                workspace,
                "固定 system prompt",
                "v1",
                "已经完成需求分析。",
                "完成需求分析"
        );
        appendCompaction(database, created.session().id(), "历史摘要：需求分析已经完成。");
        store.appendUserMessage(created.session().id(), "请开始编码。 ");

        CompactionTranscript transcript = store.loadCompactionTranscript(created.session().id());

        assertEquals("历史摘要：需求分析已经完成。", transcript.previousSummary());
        assertEquals(1, transcript.entries().size());
        assertEquals("请开始编码。 ", ((CompactionTranscript.UserEntry) transcript.entries().get(0)).content());
    }

    /** 验证缺少终态工具结果的 assistant 历史不能被伪造成合法 LLM 请求。 */
    @Test
    void rejectsLlmContextWithIncompleteToolExecution() {
        SessionDatabase database = SessionDatabase.open(temporaryDirectory.resolve("storage"));
        WorkspaceRecord workspace = insertWorkspace(database);
        SessionConversationStore store = new SessionConversationStore(database);
        SessionConversationStore.CreatedSession created = store.createFromFirstUserMessage(
                workspace,
                "固定 system prompt",
                "v1",
                "请读取配置文件。",
                "读取配置文件"
        );
        store.appendAssistantResponse(
                created.session().id(),
                new LlmResponse(
                        null,
                        List.of(new ToolCall("call-1", "Read", "{\"path\":\"one.txt\"}")),
                        FinishReason.TOOL_CALLS,
                        null
                )
        );

        assertThrows(IllegalStateException.class, () -> store.loadMessagesForLlm(created.session().id()));
        assertThrows(IllegalStateException.class, () -> store.loadCompactionTranscript(created.session().id()));
    }

    /** 在不实现压缩策略的前提下，写入一条满足既有数据库约束的 compaction 检查点。 */
    private static void appendCompaction(SessionDatabase database, String sessionId, String renderedMemory) {
        SessionPayloadCodec payloadCodec = new SessionPayloadCodec();
        SessionPayloadCodec.EncodedPayload encoded = payloadCodec.encodeMessage(
                new CompactionPayload(renderedMemory, "test-v1")
        );
        try (SqlSession sqlSession = database.openSession()) {
            SessionPersistenceMapper sessionMapper = sqlSession.getMapper(SessionPersistenceMapper.class);
            SessionMessagePersistenceMapper messageMapper = sqlSession.getMapper(SessionMessagePersistenceMapper.class);
            SessionRecord current = sessionMapper.findById(sessionId);
            SessionMessageRecord compaction = new SessionMessageRecord(
                    "message-compaction",
                    sessionId,
                    current.lastSequenceNo() + 1,
                    SessionMessageType.COMPACTION,
                    encoded.payloadVersion(),
                    encoded.payloadJson(),
                    current.lastSequenceNo(),
                    current.updatedAt() + 1
            );
            // 先推进乐观锁版本，再写入不可变 compaction 事件，保持与生产追加路径相同的原子约束。
            assertEquals(1, sessionMapper.advanceForMessageAppend(
                    sessionId,
                    current.version(),
                    current.lastSequenceNo(),
                    compaction.sequenceNo(),
                    SessionStatus.RUNNING,
                    compaction.createdAt()
            ));
            assertEquals(1, messageMapper.insert(compaction));
            sqlSession.commit();
        }
    }

    /** 插入一个已确认工作区，供会话外键关联使用。 */
    private WorkspaceRecord insertWorkspace(SessionDatabase database) {
        WorkspaceRecord workspace = new WorkspaceRecord(
                "0198a768-6e70-7000-8000-000000000001",
                temporaryDirectory.resolve("project").toAbsolutePath().normalize().toString(),
                WorkspaceType.LOCAL_DIRECTORY,
                1_000L,
                1_000L,
                "{\"formatVersion\":1}"
        );
        try (SqlSession sqlSession = database.openSession()) {
            WorkspacePersistenceMapper mapper = sqlSession.getMapper(WorkspacePersistenceMapper.class);
            assertEquals(1, mapper.insert(workspace));
            sqlSession.commit();
        }
        return workspace;
    }
}
