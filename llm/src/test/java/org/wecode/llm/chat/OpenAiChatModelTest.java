package org.wecode.llm.chat;

import org.junit.jupiter.api.Test;
import org.wecode.llm.model.Message;
import org.wecode.llm.model.ToolCall;
import org.wecode.llm.model.ToolSpec;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
