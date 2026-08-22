package org.wecode.cli.interaction;

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

    /** 记录交互层是否正确调用会话业务契约。 */
    private static final class RecordingHandler implements InteractionHandler {

        private String renamedTitle;
        private int userMessageCount;

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
        public boolean hasActiveSession() {
            return true;
        }
    }
}
