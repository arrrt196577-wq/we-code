package org.wecode.id;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * 基于 RFC 9562 的 UUIDv7 标识生成器。
 * <p>
 * UUIDv7 的高 48 位保存 Unix epoch 毫秒，剩余可变位由随机数填充；因此生成结果具备大致的时间有序性，
 * 但不保证同一毫秒内严格递增。
 */
public final class UuidV7IdGenerator implements IdGenerator {

    /** UUIDv7 时间戳可表示的最大 Unix epoch 毫秒值。 */
    private static final long MAX_TIMESTAMP_MILLIS = 0xFFFF_FFFF_FFFFL;

    /** UUIDv7 的版本字段值。 */
    private static final long VERSION_7_BITS = 0x7000L;

    /** UUID RFC 4122 variant 的最高两位。 */
    private static final long RFC_4122_VARIANT_BITS = 0x8000_0000_0000_0000L;

    /** RFC 4122 variant 之前保留的 62 位随机数掩码。 */
    private static final long RANDOM_62_BITS_MASK = 0x3FFF_FFFF_FFFF_FFFFL;

    private final Clock clock;
    private final RandomGenerator randomGenerator;

    /**
     * 使用 UTC 系统时钟和密码学安全随机源创建生成器。
     */
    public UuidV7IdGenerator() {
        this(Clock.systemUTC(), new SecureRandom());
    }

    /**
     * 使用指定的时间源和随机源创建生成器，供同包测试或受控运行环境使用。
     *
     * @param clock           提供 Unix epoch 毫秒的时间源
     * @param randomGenerator 提供 UUID 随机位的随机源
     */
    UuidV7IdGenerator(Clock clock, RandomGenerator randomGenerator) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.randomGenerator = Objects.requireNonNull(randomGenerator, "randomGenerator");
    }

    /**
     * 生成一个规范小写的 UUIDv7 文本。
     *
     * @return UUIDv7 标准文本
     */
    @Override
    public synchronized String nextId() {
        long timestampMillis = clock.millis();

        // UUIDv7 只能编码 48 位非负毫秒时间戳，拒绝截断以避免产生错误时间信息。
        if (timestampMillis < 0 || timestampMillis > MAX_TIMESTAMP_MILLIS) {
            throw new IllegalStateException("timestamp is outside UUIDv7 48-bit millisecond range: " + timestampMillis);
        }

        // 高 64 位依次写入 48 位时间戳、4 位版本号和 12 位随机数。
        long mostSignificantBits = (timestampMillis << 16)
                | VERSION_7_BITS
                | (randomGenerator.nextLong() & 0x0FFFL);
        // 低 64 位写入 RFC 4122 variant 和 62 位随机数。
        long leastSignificantBits = RFC_4122_VARIANT_BITS
                | (randomGenerator.nextLong() & RANDOM_62_BITS_MASK);

        return new UUID(mostSignificantBits, leastSignificantBits).toString();
    }
}
