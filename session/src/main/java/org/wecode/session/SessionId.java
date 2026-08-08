package org.wecode.session;

import org.wecode.id.IdGenerator;

import java.util.Objects;
import java.util.UUID;

/**
 * 会话的不可变标识值对象。
 * <p>
 * 当前会话 ID 的外部和持久化格式固定为规范小写 UUIDv7 文本。
 * 该类型避免将其他业务 ID 作为字符串误传到会话接口。
 *
 * @param value 规范小写 UUIDv7 文本
 */
public record SessionId(String value) {

    /**
     * 使用通用 ID 生成器创建新的会话标识。
     *
     * @param idGenerator 通用 ID 生成器；其输出必须符合 UUIDv7 格式
     * @return 新创建且已校验的会话标识
     */
    public static SessionId create(IdGenerator idGenerator) {
        Objects.requireNonNull(idGenerator, "idGenerator");
        return new SessionId(idGenerator.nextId());
    }

    /**
     * 将 CLI、数据库或接口输入的文本解析为会话标识。
     *
     * @param value 外部来源的会话 ID 文本
     * @return 已完成格式校验的会话标识
     */
    public static SessionId parse(String value) {
        return new SessionId(value);
    }

    /**
     * 校验会话 ID 的 UUIDv7 格式与规范文本形式。
     */
    public SessionId {
        // 空值或空白值不能作为会话标识。
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("session id must not be blank");
        }

        UUID uuid;
        try {
            uuid = UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            // 外部文本不能解析为 UUID 时，统一转换为会话 ID 的领域错误。
            throw new IllegalArgumentException("session id must be a canonical UUIDv7", exception);
        }

        // 仅接受 UUID 的规范小写文本，避免同一 ID 产生多种持久化表示。
        if (!uuid.toString().equals(value)) {
            throw new IllegalArgumentException("session id must use canonical lowercase UUID format");
        }
        // UUIDv7 必须采用 RFC 4122 variant。
        if (uuid.variant() != 2) {
            throw new IllegalArgumentException("session id must use RFC 4122 UUID variant");
        }
        // 当前会话 ID 格式固定为 UUIDv7。
        if (uuid.version() != 7) {
            throw new IllegalArgumentException("session id must be UUIDv7");
        }
    }
}
