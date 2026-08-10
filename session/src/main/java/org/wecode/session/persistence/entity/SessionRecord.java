package org.wecode.session.persistence.entity;

import java.util.Objects;

/**
 * {@code sessions} 表的一行持久化数据。
 * <p>
 * 项目根目录是会话不可变的工具访问边界；工作目录记录最近一次运行上下文，
 * 在同一项目根目录内恢复会话时允许更新。
 *
 * @param id                   会话唯一标识，由应用层生成
 * @param projectRootPath      {@code ProjectContext.projectRoot()} 对应的真实绝对路径
 * @param workingDirectoryPath {@code ProjectContext.workingDirectory()} 对应的最近工作目录
 * @param status               会话当前运行状态
 * @param lastSequenceNo       已持久化的最后一个会话事件序号
 * @param version              乐观锁版本号
 * @param createdAt            创建时间，UTC epoch milliseconds
 * @param updatedAt            最后更新时间，UTC epoch milliseconds
 * @param metadataJson         会话级扩展 JSON 对象，约定包含格式版本
 */
public record SessionRecord(
        String id,
        String projectRootPath,
        String workingDirectoryPath,
        SessionStatus status,
        long lastSequenceNo,
        long version,
        long createdAt,
        long updatedAt,
        String metadataJson
) {

    /**
     * 校验从数据库读取或即将写入数据库的会话基础字段。
     */
    public SessionRecord {
        id = requireNonBlank(id, "id");
        projectRootPath = requireNonBlank(projectRootPath, "projectRootPath");
        workingDirectoryPath = requireNonBlank(workingDirectoryPath, "workingDirectoryPath");
        status = Objects.requireNonNull(status, "status");
        metadataJson = requireNonBlank(metadataJson, "metadataJson");

        // 消息序号不允许出现负数，否则无法维持会话内的追加顺序。
        if (lastSequenceNo < 0) {
            throw new IllegalArgumentException("lastSequenceNo must be >= 0");
        }
        // 乐观锁版本号从零开始递增，负数没有业务语义。
        if (version < 0) {
            throw new IllegalArgumentException("version must be >= 0");
        }
        // 时间戳以 UTC epoch milliseconds 存储，不能为负数。
        if (createdAt < 0 || updatedAt < 0) {
            throw new IllegalArgumentException("timestamps must be >= 0");
        }
        // 会话不可能在创建前已经被更新。
        if (updatedAt < createdAt) {
            throw new IllegalArgumentException("updatedAt must be >= createdAt");
        }
    }

    /**
     * 校验必须写入的文本字段。
     *
     * @param value     待校验的字段值
     * @param fieldName 字段名称，用于异常信息
     * @return 已校验的原始字段值
     */
    private static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        // 空白值不能作为路径、标识或 JSON 内容写入持久化表。
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
