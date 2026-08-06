package org.wecode.cli;

import org.wecode.agent.AgentLoop;
import org.wecode.agent.PromptBuilder;
import org.wecode.cli.config.ConfigResolver;
import org.wecode.cli.config.LlmCliOverrides;
import org.wecode.cli.config.LlmConfig;
import org.wecode.cli.config.WeCodeConfig;
import org.wecode.cli.config.YamlConfigLoader;
import org.wecode.cli.project.ProjectContext;
import org.wecode.cli.project.ProjectResolver;
import org.wecode.llm.chat.OpenAiChatModel;
import org.wecode.llm.model.Message;
import org.wecode.session.Session;
import org.wecode.tools.impl.EditTool;
import org.wecode.tools.impl.GlobTool;
import org.wecode.tools.impl.GrepTool;
import org.wecode.tools.impl.ReadTool;
import org.wecode.tools.registry.ToolRegistry;
import org.wecode.tools.rg.RipgrepClient;
import org.wecode.tools.spi.ToolContext;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * CLI 入口：解析命令参数、识别当前项目、装配 Agent 并执行一次任务。
 * <p>
 * 当前版本仅支持一次性任务；交互式会话会在会话持久化完成后接入。
 */
@CommandLine.Command(
        name = "wecode",
        mixinStandardHelpOptions = true,
        description = "在当前 Git 项目或本地目录中执行一次自然语言编码任务。"
)
public final class Main implements Callable<Integer> {

    /** 命令行指定的配置文件；默认相对于启动目录查找。 */
    @CommandLine.Option(
            names = {"-c", "--config"},
            defaultValue = "wecode.yml",
            description = "LLM 配置文件路径，默认值：${DEFAULT-VALUE}"
    )
    private Path configPath;

    /** 本次一次性任务的自然语言文本。 */
    @CommandLine.Parameters(
            arity = "1..*",
            paramLabel = "TASK",
            description = "要交给 Agent 的自然语言任务"
    )
    private List<String> taskParts;

    private Main() {
    }

    /**
     * 解析命令行参数并执行 CLI。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }

    /**
     * 在当前项目中执行一次自然语言任务。
     *
     * @return 成功时返回 0
     */
    @Override
    public Integer call() {
        Path launchDirectory = Path.of("").toAbsolutePath().normalize();
        ProjectContext projectContext = new ProjectResolver().resolve(launchDirectory);
        String task = String.join(" ", taskParts);

        WeCodeConfig fileConfig = YamlConfigLoader.load(configPath);
        LlmConfig llm = ConfigResolver.resolveLlm(fileConfig.llm(), LlmCliOverrides.none());
        Path storageRoot = ConfigResolver.resolveStorageRoot(fileConfig.storage());
        ConfigResolver.requireApiKey(llm);

        System.out.println("config file      : " + configPath.toAbsolutePath());
        System.out.println("storage root     : " + storageRoot);
        System.out.println("launch directory : " + projectContext.launchDirectory());
        System.out.println("project root     : " + projectContext.projectRoot());
        System.out.println("project type     : " + projectContext.type());
        System.out.println("model            : " + llm.model());
        System.out.println("task             : " + task);
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
        // Glob/Grep 共享同一个 rg 客户端；本机未安装 rg 时会由工具返回明确错误。
        RipgrepClient ripgrep = RipgrepClient.fromEnvironment();
        registry.register(new GlobTool(ripgrep));
        registry.register(new GrepTool(ripgrep));
        registry.register(new EditTool());

        Session session = new Session();
        session.append(Message.system(new PromptBuilder().buildSystemPrompt()));
        session.append(Message.user(task));

        AgentLoop loop = new AgentLoop(
                chatModel,
                registry,
                ToolContext.of(projectContext.projectRoot()),
                AgentLoop.DEFAULT_MAX_STEPS,
                System.out::println
        );

        String reply = loop.run(session);
        System.out.println("---");
        System.out.println(reply == null ? "" : reply);
        return 0;
    }
}
