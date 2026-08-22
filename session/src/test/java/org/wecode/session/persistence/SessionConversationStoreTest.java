package org.wecode.session.persistence;

import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wecode.session.SessionTitle;
import org.wecode.session.persistence.entity.SessionMessageType;
import org.wecode.session.persistence.entity.SessionRecord;
import org.wecode.session.persistence.entity.SessionStatus;
import org.wecode.session.persistence.entity.SessionTitleSource;
import org.wecode.session.persistence.entity.WorkspaceRecord;
import org.wecode.session.persistence.entity.WorkspaceType;
import org.wecode.session.persistence.mapper.SessionMessagePersistenceMapper;
import org.wecode.session.persistence.mapper.SessionPersistenceMapper;
import org.wecode.session.persistence.mapper.WorkspacePersistenceMapper;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

        assertEquals(2, created.runtimeSession().messages().size());
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
