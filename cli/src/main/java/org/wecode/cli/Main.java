package org.wecode.cli;

import org.wecode.cli.config.ConfigResolver;
import org.wecode.cli.config.ResolvedProviderConfig;
import org.wecode.cli.config.WeCodeConfig;
import org.wecode.cli.config.YamlConfigLoader;
import org.wecode.cli.interaction.InteractiveSessionRunner;
import org.wecode.cli.interaction.ChatModelTitleGenerator;
import org.wecode.cli.interaction.SessionInteractionHandler;
import org.wecode.agent.compaction.CompactionService;
import org.wecode.agent.compaction.CompactionSummaryGenerator;
import org.wecode.cli.project.WorkspaceStartupResult;
import org.wecode.cli.project.WorkspaceStartupService;
import org.wecode.cli.project.WorkspaceResolver;
import org.wecode.session.persistence.SessionDatabase;
import org.wecode.session.persistence.SessionConversationStore;
import org.wecode.session.persistence.WorkspaceStore;
import org.wecode.session.persistence.entity.WorkspaceRecord;
import org.wecode.llm.chat.ChatModel;
import org.wecode.llm.provider.ChatModelFactory;
import org.wecode.tools.impl.EditTool;
import org.wecode.tools.impl.GlobTool;
import org.wecode.tools.impl.GrepTool;
import org.wecode.tools.impl.ReadTool;
import org.wecode.tools.registry.ToolRegistry;
import org.wecode.tools.rg.RipgrepClient;
import org.wecode.tools.spi.ToolContext;
import picocli.CommandLine;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * WeCode CLI 入口：确认当前真实目录是否已初始化为工作区，并进入交互会话。
 * <p>
 * 当前版本在工作区确认后进入长期交互：首条自然语言创建 session，后续输入持续追加到该 session。
 */
@CommandLine.Command(
        name = "wecode",
        mixinStandardHelpOptions = true,
        description = "检查当前目录是否为 WeCode 工作区，并在需要时创建工作区。"
)
public final class Main implements Callable<Integer> {

    /** 命令行指定的配置文件；默认相对于启动目录查找。 */
    @CommandLine.Option(
            names = {"-c", "--config"},
            defaultValue = "wecode.yml",
            description = "WeCode 配置文件路径，默认值：${DEFAULT-VALUE}"
    )
    private Path configPath;

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
     * 执行当前目录的工作区启动检查，并在成功后进入交互输入循环。
     *
     * @return 工作区已存在或已创建时返回 {@code 0}；用户取消时返回 {@code 1}
     */
    @Override
    public Integer call() {
        // 读取命令行指定或默认位置的 WeCode 配置文件。
        WeCodeConfig config = YamlConfigLoader.load(configPath);
        // 按环境变量、配置文件和系统默认值优先级解析外部数据存储目录。
        Path storageRoot = ConfigResolver.resolveStorageRoot(config.storage());
        // 打开 SQLite 数据库并自动执行尚未应用的 Flyway 迁移。
        SessionDatabase database = SessionDatabase.open(storageRoot);
        // 创建工作区持久化访问入口，供启动确认流程查询和写入工作区记录。
        WorkspaceStore workspaceStore = new WorkspaceStore(database);
        // 统一使用 UTF-8 读取标准输入，并在启动确认与后续交互间复用同一读取器。
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        // 创建当前启动目录的真实工作区路径解析器。
        WorkspaceResolver workspaceResolver = new WorkspaceResolver();
        // 组装工作区存在性检查、创建确认和取消等待的启动服务。
        WorkspaceStartupService startupService = new WorkspaceStartupService(
                // 使用数据库工作区访问入口查询或创建当前目录记录。
                workspaceStore,
                // 将启动目录解析为真实且规范化的工作区路径。
                workspaceResolver,
                // 复用标准输入读取用户的创建确认。
                input,
                // 将启动提示和结果输出到当前终端。
                System.out,
                // 在用户取消时执行可中断的等待。
                Thread::sleep
        ); // 完成工作区启动服务的依赖装配。

        WorkspaceStartupResult result = startupService.start(Path.of(""));
        // 用户未确认创建时，已由启动服务输出退出提示并等待五秒。
        if (result == WorkspaceStartupResult.CANCELLED) {
            return 1;
        }

        // 按当前启动目录解析出的工作区根路径，读取此前确认并持久化的工作区记录。
        WorkspaceRecord workspace = workspaceStore.findByRootPath(
                // 将当前工作目录解析为真实工作区路径，并转换为数据库查询使用的字符串。
                workspaceResolver.resolve(Path.of("")).workspacePath().toString()
        // 若工作区确认流程未写入对应记录，说明状态不一致，立即中止启动。
        ).orElseThrow(() -> new IllegalStateException("工作区确认成功后未找到持久化记录"));
        // 根据配置中的激活项解析实际使用的模型供应商配置。
        ResolvedProviderConfig provider = ConfigResolver.resolveActiveProvider(config.llm());
        // 使用供应商定义创建本次会话共用的聊天模型实例。
        ChatModel chatModel = ChatModelFactory.create(provider.toProviderDefinition());
        // 复用同一个会话存储入口，确保正常对话和手动压缩使用一致的数据库访问边界。
        SessionConversationStore conversationStore = new SessionConversationStore(database);
        // 组装负责处理用户交互、会话持久化和工具调用的会话处理器。
        SessionInteractionHandler interactionHandler = new SessionInteractionHandler(
            // 提供当前数据库对应的会话消息读写能力。
                conversationStore,
                // 绑定本次交互所属的工作区记录。
                workspace,
                // 使用当前聊天模型为新会话生成标题。
                new ChatModelTitleGenerator(chatModel),
                // 注入处理用户消息和生成回复的聊天模型。
                chatModel,
                // 注册本地文件读写、搜索等 Agent 工具。
                createToolRegistry(),
                // 向工具提供已规范化的当前工作区路径上下文。
                ToolContext.of(workspaceResolver.resolve(Path.of("")).workspacePath()),
                // 使用与当前会话相同的模型和存储入口生成并提交历史摘要。
                new CompactionService(conversationStore, new CompactionSummaryGenerator(chatModel))
        ); // 完成会话处理器的依赖装配。

        // 工作区可用后复用同一个输入读取器进入完整交互会话，避免 BufferedReader 预读造成输入丢失。
        // 运行交互会话循环，并将其退出码返回给 Picocli。
        return new InteractiveSessionRunner(input, System.out, interactionHandler).run();
    }

    /**
     * 注册当前 Agent 可用的本地文件工具。
     *
     * @return 已按 PromptBuilder 约定注册的工具表
     */
    private static ToolRegistry createToolRegistry() {
        ToolRegistry registry = new ToolRegistry();
        RipgrepClient ripgrep = RipgrepClient.fromEnvironment();
        registry.register(new ReadTool());
        registry.register(new GlobTool(ripgrep));
        registry.register(new GrepTool(ripgrep));
        registry.register(new EditTool());
        return registry;
    }
}
