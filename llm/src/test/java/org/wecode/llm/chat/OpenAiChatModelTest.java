package org.wecode.llm.chat;

import org.junit.jupiter.api.Test;
import org.wecode.llm.model.Message;
import org.wecode.llm.model.ToolSpec;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OpenAiChatModel} 单元测试：构造、URL、请求体拼装（不发网）。
 */
class OpenAiChatModelTest {

    @Test
    void buildsUrlFromBaseUrl() {
        OpenAiChatModel openAi = new OpenAiChatModel(
                "https://api.deepseek.com",
                "sk-test-not-real",
                "deepseek-chat",
                0.2
        );

        assertEquals("https://api.deepseek.com/chat/completions", openAi.chatCompletionsUrl());
    }

    @Test
    void trimsTrailingSlashOnBaseUrl() {
        OpenAiChatModel openAi = new OpenAiChatModel(
                "https://api.openai.com/v1/",
                "sk-test",
                "gpt-4o-mini",
                null
        );

        assertEquals("https://api.openai.com/v1/chat/completions", openAi.chatCompletionsUrl());
    }

    @Test
    void buildRequestBodyIncludesModelAndMessages() throws Exception {
        OpenAiChatModel openAi = new OpenAiChatModel(
                "https://api.deepseek.com",
                "sk-test",
                "deepseek-chat",
                0.2
        );

        String body = openAi.buildRequestBody(
                List.of(Message.user("hello")),
                List.of(ToolSpec.of("noop", "占位工具"))
        );

        assertTrue(body.contains("\"model\":\"deepseek-chat\""));
        assertTrue(body.contains("\"stream\":false"));
        assertTrue(body.contains("\"role\":\"user\""));
        assertTrue(body.contains("\"name\":\"noop\""));
        assertTrue(body.contains("\"temperature\":0.2"));
    }

    @Test
    void buildRequestBodyIncludesReasoningEffort() throws Exception {
        OpenAiChatModel openAi = new OpenAiChatModel(
                "https://api.openai.com/v1",
                "sk-test",
                "gpt-5.4",
                0.2,
                new OpenAiChatModel.ThinkingOptions("medium", true, "reasoning_content")
        );

        String body = openAi.buildRequestBody(List.of(Message.user("hello")), List.of());

        assertTrue(body.contains("\"reasoning_effort\":\"medium\""));
    }
}
