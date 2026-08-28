package org.wecode.llm.chat;

import org.junit.jupiter.api.Test;
import org.wecode.llm.model.LlmResponse;
import org.wecode.llm.model.LlmUsage;
import org.wecode.llm.model.Message;
import org.wecode.llm.model.ToolCall;
import org.wecode.llm.model.ToolSpec;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OpenAiChatModel} 单元测试：构造、URL 和请求体拼装；所有测试均不发起网络请求。
 */
class OpenAiChatModelTest {

    @Test
    void buildsUrlFromBaseUrl() {
        OpenAiChatModel openAi = new OpenAiChatModel(
                "https://api.deepseek.com",
                "sk-test-not-real",
                "deepseek-v4-flash",
                0.2
        );

        assertEquals("https://api.deepseek.com/chat/completions", openAi.chatCompletionsUrl());
    }

    @Test
    void trimsTrailingSlashOnBaseUrl() {
        OpenAiChatModel openAi = new OpenAiChatModel(
                "https://api.openai.com/v1/",
                "sk-test",
                "gpt-5.4",
                null
        );

        assertEquals("https://api.openai.com/v1/chat/completions", openAi.chatCompletionsUrl());
    }

    @Test
    void buildRequestBodyIncludesModelMessagesToolsAndTemperature() throws Exception {
        OpenAiChatModel openAi = new OpenAiChatModel(
                "https://api.deepseek.com",
                "sk-test",
                "deepseek-v4-flash",
                0.2
        );

        String body = openAi.buildRequestBody(
                List.of(Message.user("hello")),
                List.of(ToolSpec.of("noop", "占位工具"))
        );

        assertTrue(body.contains("\"model\":\"deepseek-v4-flash\""));
        assertTrue(body.contains("\"stream\":false"));
        assertTrue(body.contains("\"role\":\"user\""));
        assertTrue(body.contains("\"name\":\"noop\""));
        assertTrue(body.contains("\"temperature\":0.2"));
    }

    @Test
    void buildRequestBodyIncludesThinkingAndOmitsTemperature() throws Exception {
        OpenAiChatModel openAi = new OpenAiChatModel(
                "https://api.deepseek.com",
                "sk-test",
                "deepseek-v4-flash",
                0.2,
                new OpenAiChatModel.ThinkingOptions(true, true, "high", "reasoning_content")
        );

        String body = openAi.buildRequestBody(List.of(Message.user("hello")), List.of());

        assertTrue(body.contains("\"reasoning_effort\":\"high\""));
        assertTrue(body.contains("\"thinking\":{\"type\":\"enabled\"}"));
        assertFalse(body.contains("\"temperature\""));
    }

    @Test
    void buildRequestBodyCanSendReasoningEffortWithoutDeepSeekThinkingToggle() throws Exception {
        OpenAiChatModel openAi = new OpenAiChatModel(
                "https://www.packyapi.com/v1",
                "sk-test",
                "gpt-5.6-luna",
                null,
                new OpenAiChatModel.ThinkingOptions(true, false, "medium", "reasoning_content")
        );

        String body = openAi.buildRequestBody(List.of(Message.user("hello")), List.of());

        assertTrue(body.contains("\"reasoning_effort\":\"medium\""));
        assertFalse(body.contains("\"thinking\""));
    }

    @Test
    void buildRequestBodyReplaysReasoningForAssistantToolCall() throws Exception {
        OpenAiChatModel openAi = new OpenAiChatModel(
                "https://api.deepseek.com",
                "sk-test",
                "deepseek-v4-flash",
                null,
                new OpenAiChatModel.ThinkingOptions(true, true, "high", "reasoning_content")
        );
        Message assistant = Message.assistant(
                null,
                List.of(new ToolCall("call_1", "noop", "{}")),
                "需要先调用工具。"
        );

        String body = openAi.buildRequestBody(
                List.of(Message.user("执行任务"), assistant, Message.tool("call_1", "结果")),
                List.of(ToolSpec.of("noop", "占位工具"))
        );

        assertTrue(body.contains("\"reasoning_content\":\"需要先调用工具。\""));
        assertTrue(body.contains("\"tool_call_id\":\"call_1\""));
    }

    /** 验证 DeepSeek 非流式响应的 usage 可完整映射为统一领域模型。 */
    @Test
    void parseResponseMapsDeepSeekUsage() throws Exception {
        OpenAiChatModel openAi = createTestModel();

        LlmResponse response = openAi.parseResponse("""
                {
                  "choices": [{
                    "message": {"role": "assistant", "content": "完成"},
                    "finish_reason": "stop"
                  }],
                  "usage": {
                    "prompt_tokens": 101,
                    "completion_tokens": 29,
                    "total_tokens": 130,
                    "prompt_cache_hit_tokens": 80,
                    "prompt_cache_miss_tokens": 21,
                    "completion_tokens_details": {"reasoning_tokens": 12}
                  }
                }
                """);

        LlmUsage usage = response.usage();
        assertEquals(101, usage.inputTokens());
        assertEquals(29, usage.outputTokens());
        assertEquals(130, usage.totalTokens());
        assertEquals(80, usage.cachedInputTokens());
        assertEquals(21, usage.uncachedInputTokens());
        assertEquals(12, usage.reasoningTokens());
    }

    /** 验证兼容 Provider 缺少可选缓存、推理明细时仍保留核心 token 用量。 */
    @Test
    void parseResponseKeepsCoreUsageWhenOptionalDetailsAreMissing() throws Exception {
        OpenAiChatModel openAi = createTestModel();

        LlmResponse response = openAi.parseResponse("""
                {
                  "choices": [{
                    "message": {"role": "assistant", "content": "完成"},
                    "finish_reason": "stop"
                  }],
                  "usage": {
                    "prompt_tokens": 10,
                    "completion_tokens": 5,
                    "total_tokens": 15
                  }
                }
                """);

        assertEquals(10, response.usage().inputTokens());
        assertNull(response.usage().cachedInputTokens());
        assertNull(response.usage().uncachedInputTokens());
        assertNull(response.usage().reasoningTokens());
    }

    /** 验证 Provider 不返回 usage 或核心字段非法时，不影响正常响应解析。 */
    @Test
    void parseResponseFallsBackToNullUsageWhenUsageIsUnavailable() throws Exception {
        OpenAiChatModel openAi = createTestModel();

        LlmResponse missingUsage = openAi.parseResponse("""
                {
                  "choices": [{
                    "message": {"role": "assistant", "content": "完成"},
                    "finish_reason": "stop"
                  }]
                }
                """);
        LlmResponse invalidUsage = openAi.parseResponse("""
                {
                  "choices": [{
                    "message": {"role": "assistant", "content": "完成"},
                    "finish_reason": "stop"
                  }],
                  "usage": {
                    "prompt_tokens": -1,
                    "completion_tokens": 5,
                    "total_tokens": 4
                  }
                }
                """);

        assertNull(missingUsage.usage());
        assertNull(invalidUsage.usage());
    }

    /** 创建仅用于 JSON 构造与响应解析的固定模型实例。 */
    private static OpenAiChatModel createTestModel() {
        return new OpenAiChatModel(
                "https://api.deepseek.com",
                "sk-test",
                "deepseek-v4-flash",
                null
        );
    }
}
