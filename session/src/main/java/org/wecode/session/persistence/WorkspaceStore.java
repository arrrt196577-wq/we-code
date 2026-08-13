package org.wecode.session.persistence;

import org.apache.ibatis.session.SqlSession;
import org.wecode.id.IdGenerator;
import org.wecode.id.UuidV7IdGenerator;
import org.wecode.session.WorkspaceId;
import org.wecode.session.persistence.entity.WorkspaceRecord;
import org.wecode.session.persistence.entity.WorkspaceType;
import org.wecode.session.persistence.mapper.WorkspacePersistenceMapper;

import java.util.Objects;
import java.util.Optional;

/**
 * 工作区持久化访问入口，负责封装数据库会话与工作区 Mapper。
 */
public final class WorkspaceStore {

    private final SessionDatabase database;
    private final IdGenerator idGenerator;

    /**
     * 创建工作区持久化访问入口。
     *
     * @param database 已初始化的会话数据库
     */
    public WorkspaceStore(SessionDatabase database) {
        this(database, new UuidV7IdGenerator());
    }

    /**
     * 创建工作区持久化访问入口。
     *
     * @param database    已初始化的会话数据库
     * @param idGenerator 工作区 ID 生成器
     */
    public WorkspaceStore(SessionDatabase database, IdGenerator idGenerator) {
        this.database = Objects.requireNonNull(database, "database");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
    }

    /**
     * 创建并持久化一个本地目录工作区。
     * <p>
     * 当前仅校验路径非空且非空白，并按调用方传入的文本原样保存；路径的绝对性、格式规范化、
     * 存在性和符号链接解析不属于本方法职责。同一路径重复创建时，数据库的唯一约束负责拒绝写入。
     *
     * @param absoluteRootPath 工作区根目录的绝对路径语义文本
     * @return 已提交到数据库的工作区记录
     */
    public WorkspaceRecord create(String absoluteRootPath) {
        validateAbsoluteRootPath(absoluteRootPath);

        long createdAt = System.currentTimeMillis();
        WorkspaceRecord workspace = new WorkspaceRecord(
                WorkspaceId.create(idGenerator).value(),
                absoluteRootPath,
                WorkspaceType.LOCAL_DIRECTORY,
                createdAt,
                createdAt,
                "{\"formatVersion\":1}"
        );

        // 写入与提交必须位于同一数据库会话，确保成功返回时记录已经持久化。
        try (SqlSession sqlSession = database.openSession()) {
            WorkspacePersistenceMapper mapper = sqlSession.getMapper(WorkspacePersistenceMapper.class);
            int insertedRows = mapper.insert(workspace);
            // Mapper 未写入唯一的一行时，不能把未持久化记录伪装为创建成功。
            if (insertedRows != 1) {
                throw new IllegalStateException("workspace insert must affect exactly one row: " + insertedRows);
            }
            sqlSession.commit();
            return workspace;
        }
    }

    /**
     * 判断指定绝对路径语义的工作区是否已存在。
     * <p>
     * 当前不转换或规范化路径，传入文本会原样用于查询；因此该方法只判断相同文本的根路径
     * 是否已持久化。
     *
     * @param absoluteRootPath 待查询的工作区根目录绝对路径语义文本
     * @return 已存在匹配根路径的工作区时返回 {@code true}
     */
    public boolean existsByAbsoluteRootPath(String absoluteRootPath) {
        validateAbsoluteRootPath(absoluteRootPath);
        return findByRootPath(absoluteRootPath).isPresent();
    }

    /**
     * 按根路径精确查询工作区。
     * <p>
     * 当前阶段不转换或规范化路径，调用方传入的字符串会原样交给 Mapper 查询。
     *
     * @param rootPath 待查询的工作区根路径
     * @return 匹配的工作区；不存在时返回空
     */
    public Optional<WorkspaceRecord> findByRootPath(String rootPath) {
        validateAbsoluteRootPath(rootPath);
        // 查询方法自行管理只读数据库会话，避免调用方接触 MyBatis 资源生命周期。
        try (SqlSession sqlSession = database.openSession()) {
            WorkspacePersistenceMapper mapper = sqlSession.getMapper(WorkspacePersistenceMapper.class);
            return Optional.ofNullable(mapper.findByRootPath(rootPath));
        }
    }

    /**
     * 校验工作区根路径的当前最小输入契约。
     * <p>
     * 参数仅承载绝对路径语义；本阶段不验证绝对性，不解析符号链接，也不改变路径文本。
     *
     * @param absoluteRootPath 待校验的工作区根路径文本
     */
    private static void validateAbsoluteRootPath(String absoluteRootPath) {
        Objects.requireNonNull(absoluteRootPath, "absoluteRootPath");
        // 空白文本无法表达任何工作区根目录。
        if (absoluteRootPath.isBlank()) {
            throw new IllegalArgumentException("absoluteRootPath must not be blank");
        }
    }
}
