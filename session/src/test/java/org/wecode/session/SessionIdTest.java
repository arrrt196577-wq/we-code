package org.wecode.session;

import org.junit.jupiter.api.Test;
import org.wecode.id.UuidV7IdGenerator;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 会话 ID 格式校验与创建入口测试。 */
class SessionIdTest {

    @Test
    void createAcceptsUuidV7GeneratedByGenericGenerator() {
        SessionId sessionId = SessionId.create(new UuidV7IdGenerator());

        assertEquals(7, UUID.fromString(sessionId.value()).version());
    }

    @Test
    void createRejectsGeneratorOutputThatIsNotUuidV7() {
        // 注入错误实现时必须在会话 ID 边界失败，不能让非 UUIDv7 值继续进入业务层。
        assertThrows(IllegalArgumentException.class, () -> SessionId.create(() -> UUID.randomUUID().toString()));
    }

    @Test
    void parseRejectsBlankAndNonCanonicalValues() {
        assertThrows(IllegalArgumentException.class, () -> SessionId.parse(" "));
        assertThrows(IllegalArgumentException.class, () -> SessionId.parse("019CB514-81CA-7F4B-89CA-AF53B80AD041"));
    }

    @Test
    void parseRejectsUuidV4AndNonRfc4122Variant() {
        String uuidV4 = UUID.randomUUID().toString();
        String nonRfc4122V7 = new UUID(
                (1_723_456_789_012L << 16) | 0x7000L,
                0x0000_0000_0000_0001L
        ).toString();

        // UUIDv4 不是当前会话 ID 的持久化格式。
        assertThrows(IllegalArgumentException.class, () -> SessionId.parse(uuidV4));
        // 非 RFC 4122 variant 的 UUIDv7 不能作为会话 ID。
        assertThrows(IllegalArgumentException.class, () -> SessionId.parse(nonRfc4122V7));
    }
}
