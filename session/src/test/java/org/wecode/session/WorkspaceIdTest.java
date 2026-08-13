package org.wecode.session;

import org.junit.jupiter.api.Test;
import org.wecode.id.UuidV7IdGenerator;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 工作区 ID 格式校验与创建入口测试。 */
class WorkspaceIdTest {

    /**
     * 验证通用 ID 生成器产生的 UUIDv7 可以创建工作区 ID。
     */
    @Test
    void createAcceptsUuidV7GeneratedByGenericGenerator() {
        WorkspaceId workspaceId = WorkspaceId.create(new UuidV7IdGenerator());

        assertEquals(7, UUID.fromString(workspaceId.value()).version());
    }

    /**
     * 验证非 UUIDv7 的生成器输出会在工作区 ID 边界失败。
     */
    @Test
    void createRejectsGeneratorOutputThatIsNotUuidV7() {
        // 非 UUIDv7 值不能进入工作区持久化链路。
        assertThrows(IllegalArgumentException.class, () -> WorkspaceId.create(() -> UUID.randomUUID().toString()));
    }

    /**
     * 验证空白与非规范文本不能解析为工作区 ID。
     */
    @Test
    void parseRejectsBlankAndNonCanonicalValues() {
        assertThrows(IllegalArgumentException.class, () -> WorkspaceId.parse(" "));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceId.parse("019CB514-81CA-7F4B-89CA-AF53B80AD041"));
    }

    /**
     * 验证 UUIDv4 与非 RFC 4122 variant 的 UUIDv7 均被拒绝。
     */
    @Test
    void parseRejectsUuidV4AndNonRfc4122Variant() {
        String uuidV4 = UUID.randomUUID().toString();
        String nonRfc4122V7 = new UUID(
                (1_723_456_789_012L << 16) | 0x7000L,
                0x0000_0000_0000_0001L
        ).toString();

        // UUIDv4 不是工作区 ID 的持久化格式。
        assertThrows(IllegalArgumentException.class, () -> WorkspaceId.parse(uuidV4));
        // 非 RFC 4122 variant 的 UUIDv7 不能作为工作区 ID。
        assertThrows(IllegalArgumentException.class, () -> WorkspaceId.parse(nonRfc4122V7));
    }
}
