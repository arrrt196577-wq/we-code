package org.wecode.session.persistence.payload;

import org.junit.jupiter.api.Test;
import org.wecode.llm.model.FinishReason;
import org.wecode.llm.model.LlmResponse;
import org.wecode.llm.model.Message;
import org.wecode.llm.model.Role;
import org.wecode.llm.model.ToolCall;
import org.wecode.session.persistence.entity.SessionMessageType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证 V1 Payload 的稳定 JSON 契约、类型分派和运行时消息恢复。 */
class SessionPayloadCodecTest {

    private final SessionPayloadCodec codec = new SessionPayloadCodec();

    /** 验证四种消息载荷均由数据库类型显式分派并可无损往返。 */
    @Test
    void messagePayloadsRoundTripByExplicitType() {
        assertMessageRoundTrip(new SystemPayload("system", "system-v1"));
        assertMessageRoundTrip(new UserPayload("task"));
        assertMessageRoundTrip(new AssistantPayload("answer", "reasoning", AssistantFinishReason.STOP));
        assertMessageRoundTrip(new CompactionPayload("memory", "compaction-v1"));
    }

    /** 验证 assistant 载荷不复制工具调用，但能与工具执行记录重建完整模型消息。 */
    @Test
    void assistantPayloadRebuildsMessageWithPersistedToolCalls() {
        LlmResponse response = new LlmResponse(
                null,
                List.of(new ToolCall("call-1", "Read", "{\"path\":\"pom.xml\"}")),
                FinishReason.TOOL_CALLS,
                "先读取文件。"
        );
        AssistantPayload payload = AssistantPayload.fromResponse(response);

        Message restored = payload.toMessage(response.toolCalls());

        assertEquals(Role.ASSISTANT, restored.role());
        assertNull(restored.content());
        assertEquals(response.toolCalls(), restored.toolCalls());
        assertEquals(response.thinking(), restored.reasoningContent());
        assertEquals(AssistantFinishReason.TOOL_CALLS, payload.finishReason());
    }

    /** 验证工具结果只保存 observation，调用标识由工具执行记录补回。 */
    @Test
    void toolResultPayloadRoundTripsAndRebuildsToolMessage() {
        ToolExecutionResultPayload expected = new ToolExecutionResultPayload("file content");
        SessionPayloadCodec.EncodedPayload encoded = codec.encodeToolResult(expected);

        ToolExecutionResultPayload decoded = codec.decodeToolResult(
                encoded.payloadVersion(),
                encoded.payloadJson()
        );
        Message restored = decoded.toMessage("call-1");

        assertEquals(expected, decoded);
        assertEquals(Role.TOOL, restored.role());
        assertEquals("call-1", restored.toolCallId());
        assertEquals("file content", restored.content());
    }

    /** 未知结构版本不能被当作 V1 猜测解析。 */
    @Test
    void decodeRejectsUnknownPayloadVersion() {
        assertThrows(
                IllegalArgumentException.class,
                () -> codec.decodeMessage(SessionMessageType.USER, 2, "{\"content\":\"task\"}")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> codec.decodeToolResult(2, "{\"content\":\"result\"}")
        );
    }

    /** 类型与 JSON 结构不匹配时必须失败，不能忽略未知字段后构造错误消息。 */
    @Test
    void decodeRejectsPayloadForDifferentMessageType() {
        SessionPayloadCodec.EncodedPayload system = codec.encodeMessage(
                new SystemPayload("system", "system-v1")
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> codec.decodeMessage(SessionMessageType.USER, system.payloadVersion(), system.payloadJson())
        );
    }

    /** assistant 的停止原因必须与持久化工具调用是否存在保持一致。 */
    @Test
    void assistantRestoreRejectsIncompleteAggregate() {
        AssistantPayload toolCalling = new AssistantPayload(null, null, AssistantFinishReason.TOOL_CALLS);
        AssistantPayload stopped = new AssistantPayload("done", null, AssistantFinishReason.STOP);
        ToolCall call = new ToolCall("call-1", "Read", "{}");

        assertThrows(IllegalArgumentException.class, () -> toolCalling.toMessage(List.of()));
        assertThrows(IllegalArgumentException.class, () -> stopped.toMessage(List.of(call)));
    }

    /** 编码后使用载荷自身声明的消息类型执行往返校验。 */
    private void assertMessageRoundTrip(SessionMessagePayload expected) {
        SessionPayloadCodec.EncodedPayload encoded = codec.encodeMessage(expected);

        SessionMessagePayload decoded = codec.decodeMessage(
                expected.messageType(),
                encoded.payloadVersion(),
                encoded.payloadJson()
        );

        assertEquals(SessionPayloadCodec.VERSION_1, encoded.payloadVersion());
        assertEquals(expected, decoded);
    }
}
