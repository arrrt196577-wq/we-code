package org.wecode.cli.interaction;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Objects;

/**
 * WeCode 交互会话的输入循环。
 * <p>
 * 负责终端命令优先解析，并将自然语言交给活动 session 编排器。
 */
public final class InteractiveSessionRunner {

    private static final String PROMPT = "> ";
    private static final String EXIT_COMMAND = "/exit";
    private static final String RENAME_COMMAND = "/rename";
    private static final String RESUME_COMMAND = "/resume";

    private final BufferedReader input;
    private final PrintStream output;
    private final InteractionHandler interactionHandler;

    /**
     * 创建交互会话运行器。
     *
     * @param input  启动阶段复用的标准输入读取器，避免多个缓冲读取器争用 System.in
     * @param output 交互提示和结果的输出目标
     */
    public InteractiveSessionRunner(BufferedReader input, PrintStream output) {
        this(input, output, null);
    }

    /**
     * 创建带会话业务分发能力的交互运行器。
     *
     * @param input              启动阶段复用的标准输入读取器
     * @param output             交互提示和结果的输出目标
     * @param interactionHandler 自然语言和 rename 命令处理器
     */
    public InteractiveSessionRunner(
            BufferedReader input,
            PrintStream output,
            InteractionHandler interactionHandler
    ) {
        this.input = Objects.requireNonNull(input, "input");
        this.output = Objects.requireNonNull(output, "output");
        this.interactionHandler = interactionHandler;
    }

    /**
     * 运行交互输入循环，直到用户输入 {@code /exit} 或标准输入结束。
     *
     * @return 正常结束时返回 {@code 0}
     */
    public int run() {
        while (true) {
            output.print(PROMPT);
            output.flush();

            String line = readLine();
            // 标准输入关闭时按正常退出处理，防止在 EOF 后无限打印提示符。
            if (line == null) {
                return exitNormally();
            }

            String inputText = line.strip();
            // 仅完整匹配 /exit 才退出，包含该文本的普通对话内容不会被误判。
            if (EXIT_COMMAND.equals(inputText)) {
                return exitNormally();
            }

            // 空输入不产生任何业务动作，直接显示下一次提示符。
            if (inputText.isEmpty()) {
                continue;
            }

            dispatchInput(line, inputText);
        }
    }

    /** 根据命令优先的规则分发终端输入。 */
    private void dispatchInput(String originalLine, String inputText) {
        // 旧构造器仅供原有最小交互测试使用，不具备会话、模型或数据库依赖。
        if (interactionHandler == null) {
            output.println("该交互命令尚未实现。");
            return;
        }
        if (isCommand(inputText, RENAME_COMMAND)) {
            handleRename(inputText);
            return;
        }
        if (isCommand(inputText, RESUME_COMMAND)) {
            output.println("/resume 尚未实现。");
            return;
        }
        // 任意其他斜杠开头文本保留为本地命令命名空间，避免拼错命令时意外创建 session。
        if (inputText.startsWith("/")) {
            output.println("不支持的交互命令：" + inputText);
            return;
        }

        try {
            // 空白只用于判断；普通用户消息仍保留原始文本，避免无意改变任务内容。
            String result = interactionHandler.handleUserMessage(originalLine);
            if (result != null && !result.isBlank()) {
                output.println(result);
            }
        } catch (RuntimeException exception) {
            // 不暴露调用栈或配置密钥，保留可行动的错误文本后继续交互。
            output.println("处理会话输入失败：" + exception.getMessage());
        }
    }

    /** 解析并执行 {@code /rename <title>}。 */
    private void handleRename(String inputText) {
        String title = inputText.substring(RENAME_COMMAND.length()).strip();
        // 没有标题时不触发数据库写入。
        if (title.isEmpty()) {
            output.println("/rename 缺少标题。");
            return;
        }
        if (!interactionHandler.hasActiveSession()) {
            output.println("当前没有活动会话，无法重命名。");
            return;
        }
        try {
            interactionHandler.renameActiveSession(title);
            output.println("已重命名当前会话。");
        } catch (RuntimeException exception) {
            output.println("重命名会话失败：" + exception.getMessage());
        }
    }

    /** 判断输入是否为完整命令或紧跟空白参数的命令，防止 {@code /renamex} 被误判。 */
    private static boolean isCommand(String inputText, String command) {
        return inputText.equals(command)
                || (inputText.startsWith(command) && inputText.length() > command.length()
                && Character.isWhitespace(inputText.charAt(command.length())));
    }

    /**
     * 读取一次终端输入。
     *
     * @return 用户输入的一行文本；标准输入结束时返回 {@code null}
     */
    private String readLine() {
        try {
            return input.readLine();
        } catch (IOException exception) {
            // 输入读取失败时无法继续维持一致的交互状态，应中止启动流程。
            throw new IllegalStateException("无法读取交互输入", exception);
        }
    }

    /**
     * 输出统一的正常退出提示。
     *
     * @return 进程正常退出码 {@code 0}
     */
    private int exitNormally() {
        output.println("已退出 WeCode。");
        return 0;
    }
}
