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

/** {@link InteractiveSessionRunner} 的基本交互行为测试。 */
class InteractiveSessionRunnerTest {

    /** 验证完整匹配 /exit 时正常结束交互循环。 */
    @Test
    void exitsNormallyWhenUserEntersExitCommand() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exitCode = newRunner(" /exit \n", output).run();

        assertEquals(0, exitCode);
        assertEquals("> 已退出 WeCode。" + System.lineSeparator(), output.toString(StandardCharsets.UTF_8));
    }

    /** 验证 EOF 会正常退出，且不会重复输出交互提示符。 */
    @Test
    void exitsNormallyWhenStandardInputEnds() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exitCode = newRunner("", output).run();

        assertEquals(0, exitCode);
        assertEquals("> 已退出 WeCode。" + System.lineSeparator(), output.toString(StandardCharsets.UTF_8));
    }

    /** 验证普通输入不会被误判为退出命令，后续仍可通过 /exit 正常结束。 */
    @Test
    void keepsRunningForNonExitInput() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        int exitCode = newRunner("请执行 /exit\n/exit\n", output).run();

        assertEquals(0, exitCode);
        String outputText = output.toString(StandardCharsets.UTF_8);
        assertTrue(outputText.contains("该交互命令尚未实现。"));
        assertTrue(outputText.endsWith("已退出 WeCode。" + System.lineSeparator()));
    }

    /**
     * 创建使用内存输入输出的交互运行器。
     *
     * @param inputText 模拟终端输入
     * @param output    捕获终端输出的缓冲区
     * @return 可独立执行的交互运行器
     */
    private static InteractiveSessionRunner newRunner(String inputText, ByteArrayOutputStream output) {
        BufferedReader input = new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(inputText.getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8
        ));
        return new InteractiveSessionRunner(input, new PrintStream(output, true, StandardCharsets.UTF_8));
    }
}
