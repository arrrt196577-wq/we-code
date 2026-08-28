package org.wecode.agent;

import org.junit.jupiter.api.Test;
import org.wecode.llm.model.Message;
import org.wecode.llm.model.LlmResponse;
import org.wecode.llm.model.Role;
import org.wecode.llm.model.ToolCall;
import org.wecode.llm.model.ToolSpec;
import org.wecode.llm.model.FinishReason;
import org.wecode.session.Session;
import org.wecode.tools.registry.ToolRegistry;
import org.wecode.tools.spi.ToolContext;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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

    /** 验证外部消息来源会在每轮模型调用前重新读取，而不是被 AgentLoop 缓存在内存中。 */
    @Test
    void reloadsConversationMessagesBeforeEveryChat() {
        ToolCall toolCall = new ToolCall("call-1", "missing", "{}");
        List<List<Message>> requestedMessages = new ArrayList<>();
        AtomicInteger loadCount = new AtomicInteger();
        AgentLoop loop = new AgentLoop(
                (messages, tools) -> {
                    requestedMessages.add(messages);
                    // 首轮要求工具调用；第二轮返回最终答案。
                    if (requestedMessages.size() == 1) {
                        return new LlmResponse(null, List.of(toolCall), FinishReason.TOOL_CALLS, null);
                    }
                    return new LlmResponse("完成", List.of(), FinishReason.STOP, null);
                },
                new ToolRegistry(),
                ToolContext.of(Path.of(".")),
                2,
                null,
                AgentExecutionListener.NO_OP
        );
        ConversationMessageProvider messageProvider = () -> {
            // 第一次读取模拟首条持久化用户消息；第二次读取模拟数据库已经写入 assistant 与 tool 结果。
            if (loadCount.getAndIncrement() == 0) {
                return List.of(Message.system("固定规则"), Message.user("请执行任务"));
            }
            return List.of(
                    Message.system("固定规则"),
                    Message.user("请执行任务"),
                    Message.assistant(null, List.of(toolCall), null),
                    Message.tool("call-1", "Unknown tool: missing")
            );
        };

        String result = loop.run(messageProvider);

        assertEquals("完成", result);
        assertEquals(2, loadCount.get());
        assertEquals(2, requestedMessages.get(0).size());
        assertEquals(4, requestedMessages.get(1).size());
        assertEquals(Role.TOOL, requestedMessages.get(1).get(3).role());
    }
}
