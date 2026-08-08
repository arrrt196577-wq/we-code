package org.wecode.session.persistence;

import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wecode.session.persistence.mapper.MessagePersistenceMapper;
import org.wecode.session.persistence.mapper.SessionPersistenceMapper;

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
 * 验证会话数据库的初始化、Mapper 注册和 SQLite 外键约束。
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
            MessagePersistenceMapper messageMapper = sqlSession.getMapper(MessagePersistenceMapper.class);
            assertNotNull(sessionMapper);
            assertNotNull(messageMapper);
            assertNull(sessionMapper.findById("missing-session"));
            assertTrue(messageMapper.findAllBySessionId("missing-session").isEmpty());
            assertEquals(1, tableExists(sqlSession, "flyway_schema_history"));
            assertEquals(1, tableExists(sqlSession, "sessions"));
            assertEquals(1, tableExists(sqlSession, "messages"));
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
     * 尝试插入引用不存在会话的消息，验证当前连接已经启用 SQLite 外键约束。
     *
     * @param sqlSession 待验证的 MyBatis 数据库会话
     */
    private static void assertForeignKeyViolation(SqlSession sqlSession) throws SQLException {
        try (PreparedStatement statement = sqlSession.getConnection().prepareStatement("""
                INSERT INTO messages (
                    session_id, seq, agent_round_no, role,
                    content, tool_call_id, created_at, payload_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, "missing-session");
            statement.setLong(2, 1);
            statement.setNull(3, java.sql.Types.INTEGER);
            statement.setString(4, "USER");
            statement.setString(5, "message");
            statement.setNull(6, java.sql.Types.VARCHAR);
            statement.setLong(7, 0);
            statement.setString(8, "{}");

            // 外键不存在时必须拒绝写入，而不是留下孤儿消息记录。
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
