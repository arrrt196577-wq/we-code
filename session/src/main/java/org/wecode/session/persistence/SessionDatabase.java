package org.wecode.session.persistence;

import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 会话 SQLite 数据库的启动入口。
 * <p>
 * 本类统一管理数据库文件位置、表结构迁移和 MyBatis 工厂初始化，避免调用方自行创建不一致的连接。
 */
public final class SessionDatabase {

    /** 会话数据库在外部存储根目录下使用的固定文件名。 */
    public static final String DATABASE_FILE_NAME = "wecode.db";

    private final Path databasePath;
    private final SqlSessionFactory sqlSessionFactory;

    /**
     * 使用已完成初始化的数据源与 MyBatis 工厂创建数据库访问入口。
     *
     * @param databasePath      已规范化的 SQLite 数据库文件路径
     * @param sqlSessionFactory 已注册 Mapper 的 MyBatis 会话工厂
     */
    private SessionDatabase(Path databasePath, SqlSessionFactory sqlSessionFactory) {
        this.databasePath = databasePath;
        this.sqlSessionFactory = sqlSessionFactory;
    }

    /**
     * 打开指定外部存储根目录下的会话数据库。
     *
     * @param storageRoot WeCode 外部存储根目录，必须能被创建或已经是目录
     * @return 已完成 Flyway 迁移并可创建 MyBatis 会话的数据库入口
     */
    public static SessionDatabase open(Path storageRoot) {
        Path normalizedRoot = prepareStorageRoot(storageRoot);
        Path databasePath = normalizedRoot.resolve(DATABASE_FILE_NAME).normalize();

        // 数据源必须先创建，使 Flyway 和 MyBatis 使用完全相同的 SQLite 连接配置。
        DataSource dataSource = SessionPersistenceConfiguration.createDataSource(databasePath);
        SessionPersistenceConfiguration.migrate(dataSource);
        SqlSessionFactory sqlSessionFactory = SessionPersistenceConfiguration.createSqlSessionFactory(dataSource);
        return new SessionDatabase(databasePath, sqlSessionFactory);
    }

    /**
     * 创建一个默认不开启自动提交的 MyBatis 会话。
     *
     * @return 调用方负责关闭的数据库会话；关联写操作须由调用方显式提交或回滚
     */
    public SqlSession openSession() {
        return sqlSessionFactory.openSession();
    }

    /**
     * 返回当前会话数据库文件的绝对规范化路径。
     *
     * @return SQLite 数据库文件路径
     */
    public Path databasePath() {
        return databasePath;
    }

    /**
     * 校验并创建外部存储根目录。
     *
     * @param storageRoot 调用方提供的外部存储根目录
     * @return 已创建且规范化的绝对目录路径
     */
    private static Path prepareStorageRoot(Path storageRoot) {
        Path normalizedRoot = Objects.requireNonNull(storageRoot, "storageRoot")
                .toAbsolutePath()
                .normalize();

        try {
            // 已存在的普通文件不能作为数据库目录使用。
            if (Files.exists(normalizedRoot) && !Files.isDirectory(normalizedRoot)) {
                throw new IllegalArgumentException("storageRoot must be a directory: " + normalizedRoot);
            }
            Files.createDirectories(normalizedRoot);
            return normalizedRoot;
        } catch (IOException exception) {
            // 目录不可创建时立即失败，避免 SQLite 在不可预期的位置创建数据库文件。
            throw new IllegalStateException("Unable to create session storage directory: " + normalizedRoot, exception);
        }
    }
}
