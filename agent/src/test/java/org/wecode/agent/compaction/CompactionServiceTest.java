package org.wecode.agent.compaction;

import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wecode.llm.chat.ChatModel;
import org.wecode.llm.model.FinishReason;
import org.wecode.llm.model.LlmResponse;
import org.wecode.llm.model.Message;
import org.wecode.llm.model.Role;
import org.wecode.session.persistence.SessionConversationStore;
import org.wecode.session.persistence.SessionDatabase;
import org.wecode.session.persistence.entity.WorkspaceRecord;
import org.wecode.session.persistence.entity.WorkspaceType;
import org.wecode.session.persistence.mapper.WorkspacePersistenceMapper;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证摘要生成、格式校验和条件提交组成的完整 compaction 服务语义。
 */
class CompactionServiceTest {

    /** 用于承载每个测试独立 SQLite 数据库的临时目录。 */
    @TempDir
    Path temporaryDirectory;

    /**
     * 验证服务会写入一个检查点，并在没有新历史时跳过下一次模型调用。
     */
    @Test
    void compactsNewHistoryThenSkipsWhenThereIsNoNewContent() {
        TestFixture fixture = createFixture();
        SessionConversationStore store = fixture.store();
        String sessionId = createSession(store, fixture.workspace());
        AtomicInteger modelCalls = new AtomicInteger();
        ChatModel chatModel = (messages, tools) -> {
            modelCalls.incrementAndGet();
            return completedSummaryResponse();
        };
        CompactionService service = new CompactionService(
                store,
                new CompactionSummaryGenerator(chatModel)
        );

        // 首次调用应生成摘要、校验模板并通过快照条件写入检查点。
        CompactionResult firstResult = service.compact(sessionId);

        assertInstanceOf(CompactionResult.Compacted.class, firstResult);
        assertEquals(1, modelCalls.get());
        List<Message> recovered = store.loadMessagesForLlm(sessionId);
        assertEquals(List.of(Role.SYSTEM, Role.ASSISTANT), recovered.stream().map(Message::role).toList());
        assertTrue(recovered.get(1).content().startsWith("Historical context summary follows."));

        // 没有新增原始历史时禁止重复调用模型或追加等价检查点。
        CompactionResult secondResult = service.compact(sessionId);

        assertInstanceOf(CompactionResult.NoNewContent.class, secondResult);
        assertEquals(1, modelCalls.get());
    }

    /**
     * 验证模型调用期间新增消息会让快照过期，旧摘要不得写入。
     */
    @Test
    void returnsStaleSnapshotWhenConversationChangesDuringGeneration() {
        TestFixture fixture = createFixture();
        SessionConversationStore store = fixture.store();
        String sessionId = createSession(store, fixture.workspace());
        // 此处使用确定性本地 ChatModel 触发并发写入边界，无需真实 Provider。
        ChatModel chatModel = (messages, tools) -> {
            store.appendUserMessage(sessionId, "模型生成期间追加的消息");
            return completedSummaryResponse();
        };
        CompactionService service = new CompactionService(
                store,
                new CompactionSummaryGenerator(chatModel)
        );

        // CAS 不命中时仅报告过期快照，不能把旧摘要提交到新历史之后。
        CompactionResult result = service.compact(sessionId);

        assertInstanceOf(CompactionResult.StaleSnapshot.class, result);
        assertEquals(
                List.of(Role.SYSTEM, Role.USER, Role.USER),
                store.loadMessagesForLlm(sessionId).stream().map(Message::role).toList()
        );
    }

    /**
     * 验证模型返回不完整 Markdown 模板时，会在持久化前被拒绝。
     */
    @Test
    void rejectsMalformedSummaryBeforeCheckpointIsWritten() {
        TestFixture fixture = createFixture();
        SessionConversationStore store = fixture.store();
        String sessionId = createSession(store, fixture.workspace());
        ChatModel chatModel = (messages, tools) -> new LlmResponse(
                "## Objective\n- 缺少其余必需章节",
                List.of(),
                FinishReason.STOP,
                null
        );
        CompactionService service = new CompactionService(
                store,
                new CompactionSummaryGenerator(chatModel)
        );

        // 校验失败必须阻止任何 compaction 记录写入。
        assertThrows(IllegalStateException.class, () -> service.compact(sessionId));

        assertEquals(
                List.of(Role.SYSTEM, Role.USER),
                store.loadMessagesForLlm(sessionId).stream().map(Message::role).toList()
        );
    }

    /**
     * 创建可供服务测试使用的会话存储入口。
     *
     * @return 已初始化迁移的会话存储
     */
    private TestFixture createFixture() {
        // 每个测试使用独立数据库，避免检查点和版本号互相影响。
        SessionDatabase database = SessionDatabase.open(temporaryDirectory.resolve("storage"));
        return new TestFixture(new SessionConversationStore(database), insertWorkspace(database));
    }

    /**
     * 创建包含固定规则和一条用户消息的最小会话。
     *
     * @param store     待写入会话的持久化入口
     * @param workspace 已持久化且满足外键约束的工作区
     * @return 新创建会话的标识
     */
    private String createSession(SessionConversationStore store, WorkspaceRecord workspace) {
        // 首条用户消息会同时提供可压缩的历史来源。
        return store.createFromFirstUserMessage(
                workspace,
                "固定 system prompt",
                "v1",
                "请为当前任务生成实现方案",
                "生成实现方案"
        ).session().id();
    }

    /**
     * 向测试数据库插入满足会话外键约束的工作区。
     *
     * @param database 已初始化且与会话存储共用的测试数据库
     * @return 已持久化的工作区记录
     */
    private WorkspaceRecord insertWorkspace(SessionDatabase database) {
        WorkspaceRecord workspace = new WorkspaceRecord(
                "0198a768-6e70-7000-8000-000000000002",
                temporaryDirectory.resolve("project").toAbsolutePath().normalize().toString(),
                WorkspaceType.LOCAL_DIRECTORY,
                1_000L,
                1_000L,
                "{\"formatVersion\":1}"
        );
        try (SqlSession sqlSession = database.openSession()) {
            WorkspacePersistenceMapper mapper = sqlSession.getMapper(WorkspacePersistenceMapper.class);
            // 工作区必须先提交，后续会话创建才能满足外键约束。
            assertEquals(1, mapper.insert(workspace));
            sqlSession.commit();
        }
        return workspace;
    }

    /**
     * 构造满足固定摘要协议的无工具调用模型响应。
     *
     * @return 可由 {@link CompactionSummaryGenerator} 接受的完整响应
     */
    private static LlmResponse completedSummaryResponse() {
        // 固定内容使测试只验证编排与持久化边界，而不依赖模型输出随机性。
        return new LlmResponse(
                """
                ## Objective
                - 生成当前会话的实现方案。

                ## Important Details
                - 当前实现使用 SQLite 持久化会话。

                ## Work State
                ### Completed
                - 已创建会话。

                ### Active
                - 正在生成摘要。

                ### Blocked
                - none

                ## Next Move
                1. 继续处理用户的实现请求。
                2. 在需要时再次压缩新增历史。

                ## Relevant Files
                - none
                """,
                List.of(),
                FinishReason.STOP,
                null
        );
    }

    /**
     * 聚合单个测试需要共用的会话存储和工作区前置数据。
     *
     * @param store     会话读写入口
     * @param workspace 已落库的工作区记录
     */
    private record TestFixture(SessionConversationStore store, WorkspaceRecord workspace) {
    }
}
