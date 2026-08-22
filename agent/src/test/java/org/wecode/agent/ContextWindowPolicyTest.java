package org.wecode.agent;

import org.junit.jupiter.api.Test;
import org.wecode.llm.model.Message;
import org.wecode.llm.model.LlmResponse;
import org.wecode.llm.model.ToolCall;
import org.wecode.llm.model.ToolSpec;
import org.wecode.llm.model.FinishReason;
import org.wecode.session.Session;
import org.wecode.tools.registry.ToolRegistry;
import org.wecode.tools.spi.ToolContext;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证基于字符数的上下文窗口 MVP 规则。 */
class ContextWindowPolicyTest {

    @Test
    void estimatesMessagesAndToolsAndUsesConfiguredThreshold() {
        ContextWindowPolicy policy = new ContextWindowPolicy(
                new CharacterTokenEstimator(),
                new ContextWindowSettings(300, 40, 20)
        );
        List<Message> messages = List.of(
                Message.system("系统提示"),
                Message.assistant(
                        null,
                        List.of(new ToolCall("call-1", "read", "{\"path\":\"你好.java\"}")),
                        "reasoning"
                ),
                Message.tool("call-1", "文件内容🙂")
        );
        List<ToolSpec> tools = List.of(
                new ToolSpec("read", "读取文件", "{\"type\":\"object\",\"properties\":{}}")
        );

        ContextWindowUsage usage = policy.evaluate(messages, tools);

        assertTrue(usage.estimatedInputTokens() > 0);
        assertEquals(240, usage.compactionTriggerTokens());
        assertFalse(usage.compactionRequired());
    }

    @Test
    void requiresCompactionWhenEstimatedInputReachesTrigger() {
        ContextWindowPolicy policy = new ContextWindowPolicy(
                new CharacterTokenEstimator(),
                new ContextWindowSettings(100, 0, 20)
        );
        List<Message> messages = List.of(Message.user("x".repeat(400)));

        ContextWindowUsage usage = policy.evaluate(messages, List.of());

        assertTrue(usage.estimatedInputTokens() >= usage.compactionTriggerTokens());
        assertTrue(usage.compactionRequired());
        assertTrue(usage.remainingTokensBeforeCompaction() <= 0);
    }

    @Test
    void countsEmojiByUnicodeCodePointInsteadOfUtf16CodeUnit() {
        CharacterTokenEstimator estimator = new CharacterTokenEstimator();

        long emojiCharacters = estimator.countRequestCharacters(List.of(Message.user("🙂")), List.of());
        long asciiCharacters = estimator.countRequestCharacters(List.of(Message.user("a")), List.of());

        assertEquals(asciiCharacters, emojiCharacters);
    }

    @Test
    void calculatesContextWindowBeforeCallingChatModel() {
        AtomicReference<ContextWindowUsage> observedUsage = new AtomicReference<>();
        AgentExecutionListener listener = new AgentExecutionListener() {
            @Override
            public void onContextWindowUsage(ContextWindowUsage usage) {
                observedUsage.set(usage);
            }
        };
        AgentLoop loop = new AgentLoop(
                (messages, tools) -> new LlmResponse("完成", List.of(), FinishReason.STOP, null),
                new ToolRegistry(),
                ToolContext.of(Path.of(".")),
                1,
                null,
                listener,
                new ContextWindowPolicy(
                        new CharacterTokenEstimator(),
                        new ContextWindowSettings(300, 0, 20)
                )
        );
        Session session = new Session();
        session.append(Message.user("执行任务"));

        loop.run(session);

        assertTrue(observedUsage.get().estimatedInputTokens() > 0);
    }
}
