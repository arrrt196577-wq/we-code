package org.wecode.cli;

import org.wecode.agent.AgentLoop;
import org.wecode.agent.PromptBuilder;
import org.wecode.cli.config.ConfigResolver;
import org.wecode.cli.config.LlmCliOverrides;
import org.wecode.cli.config.LlmConfig;
import org.wecode.cli.config.WeCodeConfig;
import org.wecode.cli.config.YamlConfigLoader;
import org.wecode.llm.chat.OpenAiChatModel;
import org.wecode.llm.model.Message;
import org.wecode.session.Session;
import org.wecode.tools.impl.GlobTool;
import org.wecode.tools.impl.GrepTool;
import org.wecode.tools.impl.ReadTool;
import org.wecode.tools.registry.ToolRegistry;
import org.wecode.tools.rg.RipgrepClient;
import org.wecode.tools.spi.ToolContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * CLI 入口：解析参数、加载 yml、装配真 LLM + Read/Glob/Grep + AgentLoop 并运行任务。
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        CliArgs cliArgs = CliArgs.parse(args);

        WeCodeConfig fileConfig = YamlConfigLoader.load(cliArgs.configPath());
        LlmConfig llm = ConfigResolver.resolveLlm(fileConfig.llm(), LlmCliOverrides.none());
        ConfigResolver.requireApiKey(llm);

        // workspace 必须是已存在的目录
        Path workspace = cliArgs.workspace().toAbsolutePath().normalize();
        if (!Files.isDirectory(workspace)) {
            throw new IllegalArgumentException("workspace is not a directory: " + workspace);
        }

        System.out.println("config file : " + cliArgs.configPath().toAbsolutePath());
        System.out.println("workspace   : " + workspace);
        System.out.println("model       : " + llm.model());
        System.out.println("task        : " + cliArgs.task());
        System.out.println("---");

        OpenAiChatModel chatModel = new OpenAiChatModel(
                llm.baseUrl(),
                llm.apiKey(),
                llm.model(),
                llm.temperature(),
                new OpenAiChatModel.ThinkingOptions(
                        llm.reasoningEffort(),
                        llm.returnThinking(),
                        llm.thinkingFieldName()
                )
        );

        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadTool());
        // Glob/Grep 共用一个 rg 客户端；本机无 rg 时在此失败并提示安装
        RipgrepClient ripgrep = RipgrepClient.fromEnvironment();
        registry.register(new GlobTool(ripgrep));
        registry.register(new GrepTool(ripgrep));

        Session session = new Session();
        session.append(Message.system(new PromptBuilder().buildSystemPrompt()));
        session.append(Message.user(cliArgs.task()));

        AgentLoop loop = new AgentLoop(
                chatModel,
                registry,
                ToolContext.of(workspace),
                AgentLoop.DEFAULT_MAX_STEPS,
                System.out::println
        );

        String reply = loop.run(session);
        System.out.println("---");
        System.out.println(reply == null ? "" : reply);
    }

    /**
     * 最小 CLI：{@code --config}、{@code --workspace}、剩余参数拼成任务。
     *
     * @param configPath 配置文件路径
     * @param workspace  工作区根目录
     * @param task       用户任务文本
     */
    record CliArgs(Path configPath, Path workspace, String task) {

        /**
         * 解析命令行。
         *
         * @param args main 参数
         * @return 解析结果
         */
        static CliArgs parse(String[] args) {
            Path configPath = Path.of("wecode.yml");
            Path workspace = null;
            List<String> taskParts = new ArrayList<>();

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                // 可选配置文件
                if ("--config".equals(arg)) {
                    configPath = Path.of(requireValue(args, ++i, "--config"));
                    continue;
                }
                // 必填 workspace
                if ("--workspace".equals(arg)) {
                    workspace = Path.of(requireValue(args, ++i, "--workspace"));
                    continue;
                }
                // 以 -- 开头的未知选项
                if (arg.startsWith("--")) {
                    throw new IllegalArgumentException("unknown option: " + arg);
                }
                taskParts.add(arg);
            }

            // --workspace 必填
            if (workspace == null) {
                throw new IllegalArgumentException(
                        "missing --workspace <dir>; usage: --workspace <dir> <task...>"
                );
            }
            // 任务文本必填
            if (taskParts.isEmpty()) {
                throw new IllegalArgumentException(
                        "missing task text; usage: --workspace <dir> <task...>"
                );
            }
            return new CliArgs(configPath, workspace, String.join(" ", taskParts));
        }

        private static String requireValue(String[] args, int index, String option) {
            // 选项后必须跟一个非选项值
            if (index >= args.length || args[index].startsWith("--")) {
                throw new IllegalArgumentException(option + " requires a value");
            }
            return args[index];
        }
    }
}
