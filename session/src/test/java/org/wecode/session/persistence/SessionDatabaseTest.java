package org.wecode.session.persistence;

import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.exceptions.PersistenceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wecode.session.persistence.entity.SessionMessageRecord;
import org.wecode.session.persistence.entity.SessionMessageType;
import org.wecode.session.persistence.entity.SessionRecord;
import org.wecode.session.persistence.entity.SessionStatus;
import org.wecode.session.persistence.entity.ToolExecutionRecord;
import org.wecode.session.persistence.entity.ToolExecutionStatus;
import org.wecode.session.persistence.mapper.SessionPersistenceMapper;
import org.wecode.session.persistence.mapper.SessionMessagePersistenceMapper;
import org.wecode.session.persistence.mapper.ToolExecutionPersistenceMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证三张会话基线表的初始化、Mapper 注册和 SQLite 外键约束。
 */
class SessionDatabaseTest {

    /** JUnit 为每个测试提供的独立临时目录。 */
    @TempDir
    Path temporaryDirectory;

    /**
     * 验证首次打开时会创建数据库、执行迁移并注册全部 Mapper。
     */
    @Test
    void openCreatesDatabaseMigratesSchemaAndRegistersMappers() throws Exception {
        Path storageRoot = temporaryDirectory.resolve("storage");

        SessionDatabase database = SessionDatabase.open(storageRoot);

        assertEquals(storageRoot.resolve(SessionDatabase.DATABASE_FILE_NAME), database.databasePath());
        assertTrue(Files.isRegularFile(database.databasePath()));
        try (SqlSession sqlSession = database.openSession()) {
            // 通过实际执行查询验证 XML Mapper 已被 MyBatis 加载。
            SessionPersistenceMapper sessionMapper = sqlSession.getMapper(SessionPersistenceMapper.class);
            SessionMessagePersistenceMapper messageMapper = sqlSession.getMapper(SessionMessagePersistenceMapper.class);
            ToolExecutionPersistenceMapper toolExecutionMapper = sqlSession.getMapper(ToolExecutionPersistenceMapper.class);
            assertNotNull(sessionMapper);
            assertNotNull(messageMapper);
            assertNotNull(toolExecutionMapper);
            assertNull(sessionMapper.findById("missing-session"));
            assertTrue(messageMapper.findAllBySessionId("missing-session").isEmpty());
            assertEquals(1, tableExists(sqlSession, "flyway_schema_history"));
            assertEquals(1, tableExists(sqlSession, "sessions"));
            assertEquals(1, tableExists(sqlSession, "session_message"));
            assertEquals(1, tableExists(sqlSession, "tool_execution"));
        }
    }

    /**
     * 验证由数据源配置的外键约束对 MyBatis 创建的连接同样生效。
     */
    @Test
    void openEnforcesForeignKeysForEveryConnection() throws Exception {
        SessionDatabase database = SessionDatabase.open(temporaryDirectory.resolve("storage"));

        try (SqlSession firstSession = database.openSession()) {
            assertForeignKeyViolation(firstSession);
        }
        try (SqlSession secondSession = database.openSession()) {
            assertForeignKeyViolation(secondSession);
        }
    }

    /**
     * 验证三张基线表的 Mapper 可以写入、读取压缩节点，并完成一次工具领取和结果写回。
     */
    @Test
    void mappersPersistConversationCompactionAndToolExecution() {
        SessionDatabase database = SessionDatabase.open(temporaryDirectory.resolve("storage"));
        long createdAt = 1_000L;

        try (SqlSession sqlSession = database.openSession()) {
            SessionPersistenceMapper sessionMapper = sqlSession.getMapper(SessionPersistenceMapper.class);
            SessionMessagePersistenceMapper messageMapper = sqlSession.getMapper(SessionMessagePersistenceMapper.class);
            ToolExecutionPersistenceMapper toolMapper = sqlSession.getMapper(ToolExecutionPersistenceMapper.class);

            SessionRecord session = new SessionRecord(
                    "session-1",
                    "E:/workspace/project",
                    "E:/workspace/project",
                    SessionStatus.RUNNING,
                    0,
                    0,
                    createdAt,
                    createdAt,
                    "{\"formatVersion\":1}"
            );
            assertEquals(1, sessionMapper.insert(session));

            appendMessage(
                    sessionMapper,
                    messageMapper,
                    "session-1",
                    0,
                    0,
                    new SessionMessageRecord(
                            "message-system", "session-1", 1, SessionMessageType.SYSTEM,
                            1, "{\"content\":\"system\",\"promptVersion\":\"v1\"}", null, createdAt + 1
                    )
            );
            appendMessage(
                    sessionMapper,
                    messageMapper,
                    "session-1",
                    1,
                    1,
                    new SessionMessageRecord(
                            "message-user", "session-1", 2, SessionMessageType.USER,
                            1, "{\"content\":\"task\"}", null, createdAt + 2
                    )
            );
            // 父消息存在但不是 ASSISTANT 时，触发器仍必须拒绝创建工具调用。
            assertThrows(PersistenceException.class, () -> toolMapper.insert(new ToolExecutionRecord(
                    "invalid-tool-execution", "message-user", "invalid-call", 0, "Read", "{}",
                    ToolExecutionStatus.PENDING, null, 0, null, null, 0,
                    createdAt + 2, null, null, createdAt + 2
            )));
            appendMessage(
                    sessionMapper,
                    messageMapper,
                    "session-1",
                    2,
                    2,
                    new SessionMessageRecord(
                            "message-compaction", "session-1", 3, SessionMessageType.COMPACTION,
                            1, "{\"renderedMemory\":\"memory\"}", 2L, createdAt + 3
                    )
            );
            appendMessage(
                    sessionMapper,
                    messageMapper,
                    "session-1",
                    3,
                    3,
                    new SessionMessageRecord(
                            "message-assistant", "session-1", 4, SessionMessageType.ASSISTANT,
                            1, "{\"content\":null,\"finishReason\":\"tool_calls\"}", null, createdAt + 4
                    )
            );

            ToolExecutionRecord pendingExecution = new ToolExecutionRecord(
                    "tool-execution-1", "message-assistant", "call-1", 0, "Read", "{\"path\":\"pom.xml\"}",
                    ToolExecutionStatus.PENDING, null, 0, null, null, 0,
                    createdAt + 4, null, null, createdAt + 4
            );
            assertEquals(1, toolMapper.insert(pendingExecution));
            assertEquals(1, toolMapper.claimPending(
                    "tool-execution-1", 0, "lease-1", createdAt + 100, createdAt + 5, createdAt + 5
            ));
            assertEquals(1, toolMapper.completeRunning(
                    "tool-execution-1", 1, "lease-1", ToolExecutionStatus.SUCCEEDED,
                    "{\"content\":\"ok\",\"isError\":false}", createdAt + 6, createdAt + 6
            ));
            sqlSession.commit();
        }

        try (SqlSession sqlSession = database.openSession()) {
            SessionPersistenceMapper sessionMapper = sqlSession.getMapper(SessionPersistenceMapper.class);
            SessionMessagePersistenceMapper messageMapper = sqlSession.getMapper(SessionMessagePersistenceMapper.class);
            ToolExecutionPersistenceMapper toolMapper = sqlSession.getMapper(ToolExecutionPersistenceMapper.class);

            SessionRecord session = sessionMapper.findById("session-1");
            assertNotNull(session);
            assertEquals(4, session.lastSequenceNo());
            assertEquals(4, session.version());
            assertEquals("message-compaction", messageMapper.findLatestCompaction("session-1").id());
            assertEquals(1, messageMapper.findAfterSequenceExcludingCompaction("session-1", 2).size());

            ToolExecutionRecord completedExecution = toolMapper.findByAssistantMessageId("message-assistant").getFirst();
            assertEquals(ToolExecutionStatus.SUCCEEDED, completedExecution.status());
            assertEquals(1, completedExecution.attemptCount());
            assertEquals(2, completedExecution.revision());
        }
    }

    /**
     * 在推进会话序号和写入历史事件的同一事务中追加一条不可变消息。
     *
     * @param sessionMapper       会话根 Mapper
     * @param messageMapper       历史事件 Mapper
     * @param sessionId           会话标识
     * @param expectedVersion     当前会话版本
     * @param expectedSequenceNo  当前最后事件序号
     * @param message             即将追加的历史事件
     */
    private static void appendMessage(
            SessionPersistenceMapper sessionMapper,
            SessionMessagePersistenceMapper messageMapper,
            String sessionId,
            long expectedVersion,
            long expectedSequenceNo,
            SessionMessageRecord message
    ) {
        // 只有会话乐观锁推进成功后，才能写入对应序号的不可变历史事件。
        assertEquals(1, sessionMapper.advanceForMessageAppend(
                sessionId,
                expectedVersion,
                expectedSequenceNo,
                message.sequenceNo(),
                SessionStatus.RUNNING,
                message.createdAt()
        ));
        assertEquals(1, messageMapper.insert(message));
    }

    /**
     * 尝试插入引用不存在会话的消息和引用不存在 assistant 的工具调用，验证外键约束已开启。
     *
     * @param sqlSession 待验证的 MyBatis 数据库会话
     */
    private static void assertForeignKeyViolation(SqlSession sqlSession) throws SQLException {
        try (PreparedStatement statement = sqlSession.getConnection().prepareStatement("""
                INSERT INTO session_message (
                    id, session_id, sequence_no, message_type,
                    payload_version, payload_json, compacts_through_sequence, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, "message-1");
            statement.setString(2, "missing-session");
            statement.setLong(3, 1);
            statement.setString(4, "USER");
            statement.setInt(5, 1);
            statement.setString(6, "{\"content\":\"message\"}");
            statement.setNull(7, java.sql.Types.INTEGER);
            statement.setLong(8, 0);

            // 外键不存在时必须拒绝写入，而不是留下孤儿消息记录。
            assertThrows(SQLException.class, statement::executeUpdate);
        }

        try (PreparedStatement statement = sqlSession.getConnection().prepareStatement("""
                INSERT INTO tool_execution (
                    id, assistant_message_id, call_id, call_index, tool_name, arguments_json,
                    status, result_json, attempt_count, lease_token, lease_until, revision,
                    created_at, started_at, finished_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, "missing-session");
            statement.setString(2, "missing-assistant");
            statement.setString(3, "call-1");
            statement.setInt(4, 0);
            statement.setString(5, "Read");
            statement.setString(6, "{}");
            statement.setString(7, "PENDING");
            statement.setNull(8, java.sql.Types.VARCHAR);
            statement.setInt(9, 0);
            statement.setNull(10, java.sql.Types.VARCHAR);
            statement.setNull(11, java.sql.Types.INTEGER);
            statement.setLong(12, 0);
            statement.setLong(13, 0);
            statement.setNull(14, java.sql.Types.INTEGER);
            statement.setNull(15, java.sql.Types.INTEGER);
            statement.setLong(16, 0);

            // assistant 外键不存在时同样必须拒绝写入孤儿工具调用。
            assertThrows(SQLException.class, statement::executeUpdate);
        }
    }

    /**
     * 查询 SQLite 系统表，确认指定表是否已经由 Flyway 创建。
     *
     * @param sqlSession 已打开的 MyBatis 会话
     * @param tableName  待确认的表名
     * @return 匹配到的表数量
     */
    private static int tableExists(SqlSession sqlSession, String tableName) throws SQLException {
        try (PreparedStatement statement = sqlSession.getConnection().prepareStatement(
                "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?"
        )) {
            statement.setString(1, tableName);
            try (var resultSet = statement.executeQuery()) {
                // 统计查询始终返回一行结果。
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }
}
