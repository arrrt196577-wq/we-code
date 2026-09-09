package org.wecode.cli.interaction;

import org.wecode.agent.compaction.CompactionResult;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证交互层先解析本地命令，不会将未知斜杠命令误当作创建 session 的自然语言。
 */
class InteractiveSessionRunnerCommandTest {

    /**
     * 验证 rename 参数保留空格、resume 明确提示未实现，且普通文本才交给自然语言处理器。
     */
    @Test
    void dispatchesCommandsBeforeNaturalLanguage() {
        RecordingHandler handler = new RecordingHandler();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        InteractiveSessionRunner runner = new InteractiveSessionRunner(
                new BufferedReader(new InputStreamReader(
                        new ByteArrayInputStream(("/rename 新 标题\n/resume abc\n/unknown\n普通任务\n/exit\n")
                                .getBytes(StandardCharsets.UTF_8)),
                        StandardCharsets.UTF_8
                )),
                new PrintStream(output, true, StandardCharsets.UTF_8),
                handler
        );

        assertEquals(0, runner.run());
        assertEquals("新 标题", handler.renamedTitle);
        assertEquals(1, handler.userMessageCount);
        String outputText = output.toString(StandardCharsets.UTF_8);
        assertTrue(outputText.contains("/resume 尚未实现。"));
        assertTrue(outputText.contains("不支持的交互命令：/unknown"));
        assertTrue(outputText.contains("agent result"));
    }

    /** 验证 /compact 精确匹配时只进入压缩分支，不会被当作自然语言或未知命令。 */
    @Test
    void dispatchesExactCompactCommandWithoutEnteringNaturalLanguagePath() {
        RecordingHandler handler = new RecordingHandler(true, new CompactionResult.Compacted(12));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        InteractiveSessionRunner runner = new InteractiveSessionRunner(
                inputOf("/compact\n/compactx\n普通任务\n/exit\n"),
                new PrintStream(output, true, StandardCharsets.UTF_8),
                handler
        );

        assertEquals(0, runner.run());
        assertEquals(1, handler.compactCallCount);
        assertEquals(1, handler.userMessageCount);
        String outputText = output.toString(StandardCharsets.UTF_8);
        assertTrue(outputText.contains("当前会话已压缩。"));
        assertTrue(outputText.contains("不支持的交互命令：/compactx"));
    }

    /** 验证没有活动会话时 /compact 不触发服务，也不会创建会话或运行 Agent。 */
    @Test
    void rejectsCompactWhenThereIsNoActiveSession() {
        RecordingHandler handler = new RecordingHandler(false, new CompactionResult.Compacted(12));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        InteractiveSessionRunner runner = new InteractiveSessionRunner(
                inputOf("/compact\n/exit\n"),
                new PrintStream(output, true, StandardCharsets.UTF_8),
                handler
        );

        assertEquals(0, runner.run());
        assertEquals(0, handler.compactCallCount);
        assertEquals(0, handler.userMessageCount);
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("当前没有活动会话，无法压缩。"));
    }

    /** 验证带参数的 /compact 会被拒绝，避免错误输入仍然触发模型调用。 */
    @Test
    void rejectsArgumentsForCompactCommand() {
        RecordingHandler handler = new RecordingHandler(true, new CompactionResult.Compacted(12));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        InteractiveSessionRunner runner = new InteractiveSessionRunner(
                inputOf("/compact now\n/exit\n"),
                new PrintStream(output, true, StandardCharsets.UTF_8),
                handler
        );

        assertEquals(0, runner.run());
        assertEquals(0, handler.compactCallCount);
        assertEquals(0, handler.userMessageCount);
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("/compact 不接受参数。"));
    }

    /**
     * 创建用于终端命令测试的 UTF-8 输入。
     *
     * @param inputText 模拟用户逐行输入的文本
     * @return 可供交互运行器消费的读入器
     */
    private static BufferedReader inputOf(String inputText) {
        return new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(inputText.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8
        ));
    }

    /** 记录交互层是否正确调用会话业务契约。 */
    private static final class RecordingHandler implements InteractionHandler {

        private final boolean activeSession;
        private final CompactionResult compactionResult;
        private String renamedTitle;
        private int userMessageCount;
        private int compactCallCount;

        /**
         * @param activeSession    是否模拟存在活动会话
         * @param compactionResult 模拟压缩服务返回的结果
         */
        private RecordingHandler(boolean activeSession, CompactionResult compactionResult) {
            this.activeSession = activeSession;
            this.compactionResult = compactionResult;
        }

        /** 创建默认具有活动会话的记录处理器。 */
        private RecordingHandler() {
            this(true, new CompactionResult.Compacted(1));
        }

        @Override
        public String handleUserMessage(String userMessage) {
            userMessageCount++;
            return "agent result";
        }

        @Override
        public void renameActiveSession(String title) {
            renamedTitle = title;
        }

        @Override
        public CompactionResult compactActiveSession() {
            compactCallCount++;
            return compactionResult;
        }

        @Override
        public boolean hasActiveSession() {
            return activeSession;
        }
    }
}
