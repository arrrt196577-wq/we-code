package org.wecode.agent.compaction;

import org.junit.jupiter.api.Test;
import org.wecode.llm.chat.ChatModel;
import org.wecode.llm.chat.ChatRequestOptions;
import org.wecode.llm.model.FinishReason;
import org.wecode.llm.model.LlmResponse;
import org.wecode.llm.model.Message;
import org.wecode.llm.model.ToolSpec;
import org.wecode.session.persistence.compaction.CompactionTranscript;
import org.wecode.session.persistence.entity.ToolExecutionStatus;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 compaction 的 transcript 拼接、提示词构造和 LLM 调用约束。 */
class CompactionSummaryGeneratorTest {

    /** 验证序列化包含 reasoning、工具调用、成功/失败 observation，并截断超长工具结果。 */
    @Test
    void serializesStructuredTranscriptInConversationOrder() {
        CompactionTranscript transcript = transcriptWithToolResults("x".repeat(2_001));

        String serialized = new CompactionTranscriptSerializer().serialize(transcript);

        assertTrue(serialized.contains("[User]: 请检查配置。"));
        assertTrue(serialized.contains("[Assistant]: 我会读取配置。"));
        assertTrue(serialized.contains("[Assistant reasoning]: 先比较默认值与运行时覆盖。"));
        assertTrue(serialized.contains("[Assistant tool call]: Read({\"path\":\"config.yml\"})"));
        assertTrue(serialized.contains("[Tool result]: " + "x".repeat(2_000) + "\n[truncated]"));
        assertTrue(serialized.contains("[Tool error]: 文件不存在"));
        assertTrue(serialized.indexOf("[Assistant reasoning]") < serialized.indexOf("[Assistant tool call]"));
    }

    /** 验证提示词按固定 system + user 两条消息构造，并携带旧摘要和历史数据边界。 */
    @Test
    void buildsOpenCodeStylePromptWithPreviousSummary() {
        CompactionTranscript transcript = new CompactionTranscript(
                "旧摘要：已经定位配置入口。",
                List.of(new CompactionTranscript.UserEntry(9, "请继续修复。"))
        );

        List<Message> messages = new CompactionPromptBuilder().buildMessages(transcript);

        assertEquals(2, messages.size());
        assertEquals("SYSTEM", messages.get(0).role().name());
        assertEquals("USER", messages.get(1).role().name());
        assertTrue(messages.get(0).content().contains("untrusted historical data"));
        assertTrue(messages.get(1).content().contains("<previous-summary>\n旧摘要：已经定位配置入口。"));
        assertTrue(messages.get(1).content().contains("<conversation>\n[User]: 请继续修复。"));
        assertTrue(messages.get(1).content().contains("## Objective"));
    }

    /** 验证生成器以空工具和 2048 输出预算提交请求，并返回未持久化的摘要文本。 */
    @Test
    void submitsPromptWithoutToolsAndUsesSummaryOutputBudget() {
        AtomicReference<List<Message>> requestedMessages = new AtomicReference<>();
        AtomicReference<List<ToolSpec>> requestedTools = new AtomicReference<>();
        AtomicReference<ChatRequestOptions> requestedOptions = new AtomicReference<>();
        ChatModel chatModel = new ChatModel() {
            @Override
            public LlmResponse chat(List<Message> messages, List<ToolSpec> tools) {
                throw new AssertionError("摘要生成器必须调用带请求选项的重载");
            }

            @Override
            public LlmResponse chat(
                    List<Message> messages,
                    List<ToolSpec> tools,
                    ChatRequestOptions options
            ) {
                requestedMessages.set(messages);
                requestedTools.set(tools);
                requestedOptions.set(options);
                return new LlmResponse("## Objective\n- 修复配置", List.of(), FinishReason.STOP, null);
            }
        };

        GeneratedSummary summary = new CompactionSummaryGenerator(chatModel).generate(
                new CompactionTranscript(null, List.of(new CompactionTranscript.UserEntry(1, "请修复配置。")))
        );

        assertEquals("## Objective\n- 修复配置", summary.content());
        assertEquals(2, requestedMessages.get().size());
        assertTrue(requestedTools.get().isEmpty());
        assertEquals(2_048, requestedOptions.get().maxOutputTokens());
    }

    /** 验证摘要响应带工具调用或被输出上限截断时均不能作为可提交结果。 */
    @Test
    void rejectsToolCallingOrTruncatedSummaryResponses() {
        ChatModel toolCallingModel = (messages, tools) -> new LlmResponse(
                null,
                List.of(new org.wecode.llm.model.ToolCall("call-1", "Read", "{}")),
                FinishReason.TOOL_CALLS,
                null
        );
        ChatModel truncatedModel = (messages, tools) -> new LlmResponse(
                "不完整摘要",
                List.of(),
                FinishReason.LENGTH,
                null
        );
        CompactionTranscript transcript = new CompactionTranscript(
                null,
                List.of(new CompactionTranscript.UserEntry(1, "请修复配置。"))
        );

        assertThrows(IllegalStateException.class, () -> new CompactionSummaryGenerator(toolCallingModel).generate(transcript));
        assertThrows(IllegalStateException.class, () -> new CompactionSummaryGenerator(truncatedModel).generate(transcript));
        assertFalse(transcript.entries().isEmpty());
    }

    /** 创建包含成功和失败工具结果的固定摘要源。 */
    private static CompactionTranscript transcriptWithToolResults(String successfulResult) {
        return new CompactionTranscript(
                null,
                List.of(
                        new CompactionTranscript.UserEntry(1, "请检查配置。"),
                        new CompactionTranscript.AssistantEntry(
                                2,
                                "我会读取配置。",
                                "先比较默认值与运行时覆盖。",
                                List.of(
                                        new CompactionTranscript.ToolExecutionEntry(
                                                "call-1",
                                                "Read",
                                                "{\"path\":\"config.yml\"}",
                                                ToolExecutionStatus.SUCCEEDED,
                                                successfulResult
                                        ),
                                        new CompactionTranscript.ToolExecutionEntry(
                                                "call-2",
                                                "Read",
                                                "{\"path\":\"missing.yml\"}",
                                                ToolExecutionStatus.FAILED,
                                                "文件不存在"
                                        )
                                )
                        )
                )
        );
    }
}
