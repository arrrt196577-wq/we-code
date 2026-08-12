package org.wecode.session.persistence.payload;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.wecode.session.persistence.entity.SessionMessageRecord;
import org.wecode.session.persistence.entity.SessionMessageType;

import java.util.Objects;

/**
 * 会话持久化 Payload 的显式版本化 JSON 编解码器。
 *
 * <p>类型由数据库列决定，Codec 不使用 Jackson Default Typing，也不会把 Java 类名写进持久化 JSON。</p>
 */
public final class SessionPayloadCodec {

    /** 当前五种 Payload 的首个持久化结构版本。 */
    public static final int VERSION_1 = 1;

    private final ObjectMapper objectMapper;

    /** 创建拒绝未知字段的严格 Codec，防止错误版本被静默解析。 */
    public SessionPayloadCodec() {
        this.objectMapper = new ObjectMapper()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    /**
     * 编码一条会话消息载荷。
     *
     * @param payload 已完成领域校验的载荷
     * @return 同时包含结构版本与 JSON 的编码结果
     */
    public EncodedPayload encodeMessage(SessionMessagePayload payload) {
        Objects.requireNonNull(payload, "payload");
        // 当前所有消息载荷都从版本 1 起步，后续版本必须在此显式分派。
        return encodeVersion1(payload);
    }

    /**
     * 按消息记录声明的类型和版本解码载荷。
     *
     * @param record 数据库消息记录
     * @return 与 messageType 匹配的载荷
     */
    public SessionMessagePayload decodeMessage(SessionMessageRecord record) {
        Objects.requireNonNull(record, "record");
        // 数据库列是唯一类型与版本判别来源。
        return decodeMessage(record.messageType(), record.payloadVersion(), record.payloadJson());
    }

    /**
     * 按显式类型和版本解码会话消息载荷。
     *
     * @param messageType    数据库消息类型
     * @param payloadVersion 数据库载荷版本
     * @param payloadJson    JSON 文本
     * @return 类型安全的会话消息载荷
     */
    public SessionMessagePayload decodeMessage(
            SessionMessageType messageType,
            int payloadVersion,
            String payloadJson
    ) {
        Objects.requireNonNull(messageType, "messageType");
        Objects.requireNonNull(payloadJson, "payloadJson");
        // 未知版本不能按 V1 猜测解析，否则恢复后可能静默丢失字段。
        if (payloadVersion != VERSION_1) {
            throw new IllegalArgumentException("Unsupported message payload version: " + payloadVersion);
        }

        Class<? extends SessionMessagePayload> payloadClass = switch (messageType) {
            case SYSTEM -> SystemPayload.class;
            case USER -> UserPayload.class;
            case ASSISTANT -> AssistantPayload.class;
            case COMPACTION -> CompactionPayload.class;
        };
        // 类型由 message_type 决定，不信任 JSON 自行声明类型。
        return read(payloadJson, payloadClass, "message " + messageType);
    }

    /**
     * 编码工具执行结果载荷。
     *
     * @param payload 工具执行结果
     * @return 同时包含结构版本与 JSON 的编码结果
     */
    public EncodedPayload encodeToolResult(ToolExecutionResultPayload payload) {
        Objects.requireNonNull(payload, "payload");
        // 工具结果独立于消息类型分派，但共享相同的版本文档结构。
        return encodeVersion1(payload);
    }

    /**
     * 解码工具执行结果载荷。
     *
     * @param payloadVersion 工具结果结构版本
     * @param payloadJson    工具结果 JSON
     * @return 类型安全的工具结果载荷
     */
    public ToolExecutionResultPayload decodeToolResult(int payloadVersion, String payloadJson) {
        Objects.requireNonNull(payloadJson, "payloadJson");
        // 未知版本必须交给后续升级后的 Codec 处理。
        if (payloadVersion != VERSION_1) {
            throw new IllegalArgumentException("Unsupported tool result payload version: " + payloadVersion);
        }
        // 工具结果类型由 tool_execution.result_json 字段语义固定。
        return read(payloadJson, ToolExecutionResultPayload.class, "tool result");
    }

    /** 编码当前 V1 Java 对象。 */
    private EncodedPayload encodeVersion1(Object payload) {
        try {
            // 版本号与 JSON 一起返回，调用方不能只写入其中一部分。
            return new EncodedPayload(VERSION_1, objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException exception) {
            // 已校验的 record 理论上可序列化；失败说明持久化契约或 Jackson 配置错误。
            throw new IllegalStateException("Unable to encode session payload", exception);
        }
    }

    /** 按已知类严格读取 JSON。 */
    private <T> T read(String payloadJson, Class<T> payloadClass, String payloadLabel) {
        try {
            // Record 构造器继续执行领域字段校验。
            return objectMapper.readValue(payloadJson, payloadClass);
        } catch (JsonProcessingException exception) {
            // 数据库载荷不合法时立即停止恢复，避免构造部分会话。
            throw new IllegalArgumentException("Unable to decode " + payloadLabel + " payload", exception);
        }
    }

    /**
     * 不可拆分写入数据库的版本化 JSON 文档。
     *
     * @param payloadVersion 载荷结构版本
     * @param payloadJson    序列化 JSON
     */
    public record EncodedPayload(int payloadVersion, String payloadJson) {

        /** 校验编码结果可直接写入数据库。 */
        public EncodedPayload {
            // 版本从 1 开始递增。
            if (payloadVersion <= 0) {
                throw new IllegalArgumentException("payloadVersion must be > 0");
            }
            Objects.requireNonNull(payloadJson, "payloadJson");
            // 空白字符串不是有效 JSON 文档。
            if (payloadJson.isBlank()) {
                throw new IllegalArgumentException("payloadJson must not be blank");
            }
        }
    }
}
