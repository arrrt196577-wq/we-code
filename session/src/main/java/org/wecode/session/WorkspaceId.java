package org.wecode.session;

import org.wecode.id.IdGenerator;

import java.util.Objects;
import java.util.UUID;

/**
 * 工作区不可变标识值对象。
 * <p>
 * 当前工作区 ID 的外部和持久化格式固定为规范小写 UUIDv7 文本，防止其他业务 ID
 * 以普通字符串形式误传入工作区持久化链路。
 *
 * @param value 规范小写 UUIDv7 文本
 */
public record WorkspaceId(String value) {

    /**
     * 使用通用 ID 生成器创建新的工作区标识。
     *
     * @param idGenerator 通用 ID 生成器；其输出必须符合 UUIDv7 格式
     * @return 新创建且已校验的工作区标识
     */
    public static WorkspaceId create(IdGenerator idGenerator) {
        Objects.requireNonNull(idGenerator, "idGenerator");
        return new WorkspaceId(idGenerator.nextId());
    }

    /**
     * 将数据库或外部输入的文本解析为工作区标识。
     *
     * @param value 外部来源的工作区 ID 文本
     * @return 已完成格式校验的工作区标识
     */
    public static WorkspaceId parse(String value) {
        return new WorkspaceId(value);
    }

    /**
     * 校验工作区 ID 的 UUIDv7 格式与规范文本形式。
     */
    public WorkspaceId {
        // 空值或空白值不能作为工作区标识。
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("workspace id must not be blank");
        }

        UUID uuid;
        try {
            uuid = UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            // 外部文本不能解析为 UUID 时，统一转换为工作区 ID 的领域错误。
            throw new IllegalArgumentException("workspace id must be a canonical UUIDv7", exception);
        }

        // 仅接受 UUID 的规范小写文本，避免同一 ID 产生多种持久化表示。
        if (!uuid.toString().equals(value)) {
            throw new IllegalArgumentException("workspace id must use canonical lowercase UUID format");
        }
        // UUIDv7 必须采用 RFC 4122 variant。
        if (uuid.variant() != 2) {
            throw new IllegalArgumentException("workspace id must use RFC 4122 UUID variant");
        }
        // 当前工作区 ID 格式固定为 UUIDv7。
        if (uuid.version() != 7) {
            throw new IllegalArgumentException("workspace id must be UUIDv7");
        }
    }
}
