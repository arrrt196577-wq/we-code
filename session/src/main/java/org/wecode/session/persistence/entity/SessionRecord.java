package org.wecode.session.persistence.entity;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * {@code sessions} 表的一行持久化数据。
 * <p>
 * 工作区和工作目录共同构成会话不可变的执行上下文。
 *
 * @param id                   会话唯一标识，由应用层生成
 * @param workspaceId                    所属工作区标识
 * @param workingDirectoryRelativePath   相对于工作区根目录的固定工作目录，根目录用 {@code .} 表示
 * @param status               会话当前运行状态
 * @param lastSequenceNo       已持久化的最后一个会话事件序号
 * @param version              乐观锁版本号
 * @param createdAt            创建时间，UTC epoch milliseconds
 * @param updatedAt            最后更新时间，UTC epoch milliseconds
 * @param metadataJson         会话级扩展 JSON 对象，约定包含格式版本
 */
public record SessionRecord(
        String id,
        String workspaceId,
        String workingDirectoryRelativePath,
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
        workspaceId = requireNonBlank(workspaceId, "workspaceId");
        workingDirectoryRelativePath = requireNonBlank(
                workingDirectoryRelativePath,
                "workingDirectoryRelativePath"
        );
        workingDirectoryRelativePath = requireNormalizedRelativePath(workingDirectoryRelativePath);
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

    /**
     * 校验会话工作目录必须是规范化的相对路径，防止恢复时越过工作区边界。
     *
     * @param value 待校验的相对路径文本
     * @return 已校验且统一使用正斜杠的相对路径；工作区根目录返回 {@code .}
     */
    private static String requireNormalizedRelativePath(String value) {
        try {
            Path path = Path.of(value);
            // 绝对路径会绕过 workspace 根目录拼接，不能作为会话工作目录保存。
            if (path.isAbsolute()) {
                throw new IllegalArgumentException("workingDirectoryRelativePath must be relative: " + value);
            }

            Path normalized = path.normalize();
            String normalizedValue = normalized.toString().replace('\\', '/');
            // 空相对路径没有稳定的持久化表示，统一使用点表示工作区根目录。
            if (normalizedValue.isBlank()) {
                normalizedValue = ".";
            }
            // 规范化后仍以父目录开头，代表工作目录落在 workspace 边界之外。
            if (normalizedValue.equals("..") || normalizedValue.startsWith("../")) {
                throw new IllegalArgumentException(
                        "workingDirectoryRelativePath must stay inside workspace: " + value
                );
            }
            // 只存规范形式，避免同一目录出现多个数据库身份。
            if (!normalizedValue.equals(value.replace('\\', '/'))) {
                throw new IllegalArgumentException(
                        "workingDirectoryRelativePath must be normalized: " + value
                );
            }
            return normalizedValue;
        } catch (InvalidPathException exception) {
            throw new IllegalArgumentException("workingDirectoryRelativePath is invalid: " + value, exception);
        }
    }
}
