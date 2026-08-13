package org.wecode.session.persistence;

import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wecode.id.UuidV7IdGenerator;
import org.wecode.session.persistence.entity.WorkspaceRecord;
import org.wecode.session.persistence.entity.WorkspaceType;
import org.wecode.session.persistence.mapper.WorkspacePersistenceMapper;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证工作区持久化入口能够通过根路径查询工作区。
 */
class WorkspaceStoreTest {

    /** JUnit 为每个测试提供的独立临时目录。 */
    @TempDir
    Path temporaryDirectory;

    /**
     * 验证创建入口补齐固定字段并提交工作区记录。
     */
    @Test
    void createsAndPersistsLocalDirectoryWorkspace() {
        SessionDatabase database = SessionDatabase.open(temporaryDirectory.resolve("storage"));
        String rootPath = temporaryDirectory.resolve("project").toAbsolutePath().toString();

        WorkspaceRecord created = new WorkspaceStore(database, new UuidV7IdGenerator()).create(rootPath);

        assertEquals(rootPath, created.rootPath());
        assertEquals(WorkspaceType.LOCAL_DIRECTORY, created.type());
        assertEquals(created.createdAt(), created.trustedAt());
        assertEquals("{\"formatVersion\":1}", created.metadataJson());
        assertEquals(7, UUID.fromString(created.id()).version());
        assertEquals(Optional.of(created), new WorkspaceStore(database).findByRootPath(rootPath));
    }

    /**
     * 验证空白路径和重复根路径都不能创建第二个工作区。
     */
    @Test
    void rejectsBlankAndDuplicateRootPaths() {
        SessionDatabase database = SessionDatabase.open(temporaryDirectory.resolve("storage"));
        WorkspaceStore store = new WorkspaceStore(database, new UuidV7IdGenerator());
        String rootPath = temporaryDirectory.resolve("project").toAbsolutePath().toString();

        // 空白值不具备工作区路径语义，必须在访问数据库前失败。
        assertThrows(IllegalArgumentException.class, () -> store.create(" "));
        store.create(rootPath);
        // 重复路径必须由 root_path 的唯一约束拒绝，不能静默复用已有记录。
        assertThrows(RuntimeException.class, () -> store.create(rootPath));
    }

    /**
     * 验证存在性查询仅按原始路径文本判断记录是否存在。
     */
    @Test
    void checksWorkspaceExistenceByRawAbsoluteRootPath() {
        SessionDatabase database = SessionDatabase.open(temporaryDirectory.resolve("storage"));
        WorkspaceStore store = new WorkspaceStore(database, new UuidV7IdGenerator());
        String rootPath = temporaryDirectory.resolve("project").toAbsolutePath().toString();
        String missingRootPath = temporaryDirectory.resolve("missing").toAbsolutePath().toString();

        assertTrue(!store.existsByAbsoluteRootPath(rootPath));
        store.create(rootPath);
        assertTrue(store.existsByAbsoluteRootPath(rootPath));
        assertTrue(!store.existsByAbsoluteRootPath(missingRootPath));
        // 空白和空值不具备工作区根路径语义，必须在查询前失败。
        assertThrows(IllegalArgumentException.class, () -> store.existsByAbsoluteRootPath(" "));
        assertThrows(NullPointerException.class, () -> store.existsByAbsoluteRootPath(null));
    }

    /**
     * 验证查询入口将根路径交给 Mapper，并返回对应工作区。
     */
    @Test
    void findsWorkspaceByRootPath() {
        SessionDatabase database = SessionDatabase.open(temporaryDirectory.resolve("storage"));
        String rootPath = temporaryDirectory.resolve("project").toAbsolutePath().normalize().toString();
        WorkspaceRecord expected = new WorkspaceRecord(
                "workspace-1",
                rootPath,
                WorkspaceType.LOCAL_DIRECTORY,
                1_000L,
                1_000L,
                "{\"formatVersion\":1}"
        );

        // 先通过 Mapper 写入基线数据，再验证 WorkspaceStore 的实际查询链路。
        try (SqlSession sqlSession = database.openSession()) {
            WorkspacePersistenceMapper mapper = sqlSession.getMapper(WorkspacePersistenceMapper.class);
            assertEquals(1, mapper.insert(expected));
            sqlSession.commit();
        }

        WorkspaceStore store = new WorkspaceStore(database);

        assertEquals(Optional.of(expected), store.findByRootPath(rootPath));
        assertTrue(store.findByRootPath(temporaryDirectory.resolve("missing").toString()).isEmpty());
    }
}
