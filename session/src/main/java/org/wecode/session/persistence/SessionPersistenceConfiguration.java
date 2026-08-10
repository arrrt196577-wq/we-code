package org.wecode.session.persistence;

import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;
import org.wecode.session.persistence.mapper.SessionPersistenceMapper;
import org.wecode.session.persistence.mapper.SessionMessagePersistenceMapper;
import org.wecode.session.persistence.mapper.ToolExecutionPersistenceMapper;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 组装会话持久化所需的 SQLite、Flyway 与 MyBatis 基础设施。
 */
final class SessionPersistenceConfiguration {

    /** SQLite 遇到短暂写锁时的最大等待时间。 */
    private static final int BUSY_TIMEOUT_MILLIS = 5_000;

    private SessionPersistenceConfiguration() {
    }

    /**
     * 创建对每个连接启用外键约束的 SQLite 数据源。
     *
     * @param databasePath SQLite 数据库文件的绝对规范化路径
     * @return 同时供 Flyway 和 MyBatis 使用的数据源
     */
    static DataSource createDataSource(Path databasePath) {
        Path normalizedPath = Objects.requireNonNull(databasePath, "databasePath")
                .toAbsolutePath()
                .normalize();
        SQLiteConfig sqliteConfig = new SQLiteConfig();
        sqliteConfig.enforceForeignKeys(true);
        sqliteConfig.setBusyTimeout(BUSY_TIMEOUT_MILLIS);

        SQLiteDataSource dataSource = new SQLiteDataSource(sqliteConfig);
        dataSource.setUrl("jdbc:sqlite:" + normalizedPath.toString().replace('\\', '/'));
        return dataSource;
    }

    /**
     * 执行会话数据库尚未应用的 Flyway 迁移。
     *
     * @param dataSource 已配置 SQLite 连接选项的数据源
     */
    static void migrate(DataSource dataSource) {
        Flyway.configure()
                .dataSource(Objects.requireNonNull(dataSource, "dataSource"))
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    /**
     * 创建注册会话 Mapper 的 MyBatis 会话工厂。
     *
     * @param dataSource 已完成迁移的数据源
     * @return 可开启数据库事务会话的 MyBatis 工厂
     */
    static SqlSessionFactory createSqlSessionFactory(DataSource dataSource) {
        Environment environment = new Environment(
                "wecode-sqlite",
                new JdbcTransactionFactory(),
                Objects.requireNonNull(dataSource, "dataSource")
        );
        Configuration configuration = new Configuration(environment);
        configuration.addMapper(SessionPersistenceMapper.class);
        configuration.addMapper(SessionMessagePersistenceMapper.class);
        configuration.addMapper(ToolExecutionPersistenceMapper.class);
        return new SqlSessionFactoryBuilder().build(configuration);
    }
}
