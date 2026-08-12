package org.wecode.session.persistence.entity;

import java.util.Objects;

/**
 * {@code workspaces} 表的一行持久化数据。
 *
 * @param id           工作区唯一标识，由应用层生成
 * @param rootPath     用户确认的真实绝对路径，也是不可变的工具访问边界
 * @param type         工作区识别来源
 * @param trustedAt    用户确认信任的时刻，UTC epoch milliseconds
 * @param createdAt    工作区记录创建时刻，UTC epoch milliseconds
 * @param metadataJson 工作区扩展 JSON 对象，约定包含格式版本
 */
public record WorkspaceRecord(
        String id,
        String rootPath,
        WorkspaceType type,
        long trustedAt,
        long createdAt,
        String metadataJson
) {

    /**
     * 校验从数据库读取或即将写入数据库的工作区字段。
     */
    public WorkspaceRecord {
        id = requireNonBlank(id, "id");
        rootPath = requireNonBlank(rootPath, "rootPath");
        type = Objects.requireNonNull(type, "type");
        metadataJson = requireNonBlank(metadataJson, "metadataJson");

        // 时间戳以 UTC epoch milliseconds 存储，不能为负数。
        if (trustedAt < 0 || createdAt < 0) {
            throw new IllegalArgumentException("timestamps must be >= 0");
        }
        // 信任动作不能发生在工作区记录创建之前。
        if (trustedAt < createdAt) {
            throw new IllegalArgumentException("trustedAt must be >= createdAt");
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
        // 空白值不能作为标识、路径或 JSON 内容写入持久化表。
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
