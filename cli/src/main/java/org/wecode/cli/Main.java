package org.wecode.cli;

import org.wecode.cli.config.ConfigResolver;
import org.wecode.cli.config.WeCodeConfig;
import org.wecode.cli.config.YamlConfigLoader;
import org.wecode.cli.project.WorkspaceStartupResult;
import org.wecode.cli.project.WorkspaceStartupService;
import org.wecode.cli.project.WorkspaceResolver;
import org.wecode.session.persistence.SessionDatabase;
import org.wecode.session.persistence.WorkspaceStore;
import picocli.CommandLine;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * WeCode CLI 入口：确认当前真实目录是否已初始化为工作区。
 * <p>
 * 当前版本只支持裸命令 {@code wecode} 的工作区检查与创建确认，不接收任务参数，也不启动 Agent。
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
     * 执行当前目录的工作区启动检查。
     *
     * @return 工作区已存在或已创建时返回 {@code 0}；用户取消时返回 {@code 1}
     */
    @Override
    public Integer call() {
        WeCodeConfig config = YamlConfigLoader.load(configPath);
        Path storageRoot = ConfigResolver.resolveStorageRoot(config.storage());
        WorkspaceStore workspaceStore = new WorkspaceStore(SessionDatabase.open(storageRoot));
        WorkspaceStartupService startupService = new WorkspaceStartupService(
                workspaceStore,
                new WorkspaceResolver(),
                new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)),
                System.out,
                Thread::sleep
        );

        WorkspaceStartupResult result = startupService.start(Path.of(""));
        // 用户未确认创建时，已由启动服务输出退出提示并等待五秒。
        return result == WorkspaceStartupResult.CANCELLED ? 1 : 0;
    }
}
