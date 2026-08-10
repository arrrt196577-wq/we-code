package org.wecode.session.persistence.entity;

import java.util.Objects;

/**
 * {@code session_message} 表的一条不可变历史事件。
 *
 * @param id                       消息自身唯一标识
 * @param sessionId                所属会话标识
 * @param sequenceNo               会话内从 1 开始的严格递增序号
 * @param messageType              历史事件类型
 * @param payloadVersion           当前类型 payload 的格式版本
 * @param payloadJson              类型专属的 JSON 载荷
 * @param compactsThroughSequence  compaction 覆盖的最后历史序号；其他类型必须为 {@code null}
 * @param createdAt                追加时间，UTC epoch milliseconds
 */
public record SessionMessageRecord(
        String id,
        String sessionId,
        long sequenceNo,
        SessionMessageType messageType,
        int payloadVersion,
        String payloadJson,
        Long compactsThroughSequence,
        long createdAt
) {

    /**
     * 校验历史事件的通用边界，JSON 的语法合法性由 SQLite 约束保证。
     */
    public SessionMessageRecord {
        id = requireNonBlank(id, "id");
        sessionId = requireNonBlank(sessionId, "sessionId");
        messageType = Objects.requireNonNull(messageType, "messageType");
        payloadJson = requireNonBlank(payloadJson, "payloadJson");

        // 会话内序号从 1 开始，零仅表示 sessions 表中的空会话。
        if (sequenceNo <= 0) {
            throw new IllegalArgumentException("sequenceNo must be > 0");
        }
        // 每种 payload 都必须声明其格式版本，以支持后续演进。
        if (payloadVersion <= 0) {
            throw new IllegalArgumentException("payloadVersion must be > 0");
        }
        // 持久化时间统一采用非负 UTC epoch milliseconds。
        if (createdAt < 0) {
            throw new IllegalArgumentException("createdAt must be >= 0");
        }
        validateCompactionBoundary(messageType, sequenceNo, compactsThroughSequence);
    }

    /**
     * 校验 compaction 的覆盖边界不能指向自身或未来事件。
     *
     * @param messageType             当前事件类型
     * @param sequenceNo              当前事件序号
     * @param compactsThroughSequence 压缩覆盖边界
     */
    private static void validateCompactionBoundary(
            SessionMessageType messageType,
            long sequenceNo,
            Long compactsThroughSequence
    ) {
        // 仅 compaction 可以声明历史覆盖范围。
        if (messageType != SessionMessageType.COMPACTION) {
            if (compactsThroughSequence != null) {
                throw new IllegalArgumentException("only COMPACTION may have compactsThroughSequence");
            }
            return;
        }
        // compaction 必须覆盖至少一条、且早于自身的消息。
        if (compactsThroughSequence == null
                || compactsThroughSequence <= 0
                || compactsThroughSequence >= sequenceNo) {
            throw new IllegalArgumentException("COMPACTION must cover an earlier positive sequence");
        }
    }

    /**
     * 校验不可为空白的标识与 JSON 文本。
     *
     * @param value     待校验文本
     * @param fieldName 字段名称，用于异常信息
     * @return 已校验的原始文本
     */
    private static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        // 空白标识和空白 payload 都没有可持久化语义。
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
