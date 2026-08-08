package org.wecode.id;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** UUIDv7 生成器的格式、时间戳与唯一性测试。 */
class UuidV7IdGeneratorTest {

    @Test
    void nextIdUsesUuidV7AndEncodesClockMillis() {
        long expectedMillis = 1_723_456_789_012L;
        UuidV7IdGenerator generator = new UuidV7IdGenerator(
                Clock.fixed(Instant.ofEpochMilli(expectedMillis), ZoneOffset.UTC),
                new Random(7)
        );

        String generatedId = generator.nextId();
        UUID id = UUID.fromString(generatedId);

        assertEquals(7, id.version());
        assertEquals(2, id.variant());
        assertEquals(expectedMillis, id.getMostSignificantBits() >>> 16);
        assertEquals(id.toString(), generatedId);
    }

    @Test
    void nextIdGeneratesUniqueIdsWithinSameMillisecond() {
        UuidV7IdGenerator generator = new UuidV7IdGenerator(
                Clock.fixed(Instant.ofEpochMilli(1_723_456_789_012L), ZoneOffset.UTC),
                new Random(11)
        );
        Set<String> ids = new HashSet<>();

        // 同一毫秒内连续生成，验证随机位可区分每个 ID。
        for (int index = 0; index < 10_000; index++) {
            ids.add(generator.nextId());
        }

        assertEquals(10_000, ids.size());
    }

    @Test
    void nextIdRejectsTimestampOutsideUuidV7Range() {
        UuidV7IdGenerator beforeEpochGenerator = new UuidV7IdGenerator(
                Clock.fixed(Instant.ofEpochMilli(-1), ZoneOffset.UTC),
                new Random(13)
        );
        UuidV7IdGenerator overflowingGenerator = new UuidV7IdGenerator(
                Clock.fixed(Instant.ofEpochMilli(0x1_0000_0000_0000L), ZoneOffset.UTC),
                new Random(17)
        );

        // 时间戳在 epoch 前时不能编码为 UUIDv7。
        IllegalStateException beforeEpoch = assertThrows(IllegalStateException.class, beforeEpochGenerator::nextId);
        // 时间戳超出 48 位范围时不能静默截断。
        IllegalStateException overflowing = assertThrows(IllegalStateException.class, overflowingGenerator::nextId);

        assertTrue(beforeEpoch.getMessage().contains("outside UUIDv7"));
        assertTrue(overflowing.getMessage().contains("outside UUIDv7"));
    }
}
