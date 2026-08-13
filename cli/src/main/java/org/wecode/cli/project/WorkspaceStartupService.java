package org.wecode.cli.project;

import org.wecode.session.persistence.WorkspaceStore;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/**
 * 编排 WeCode 启动时的工作区检查、创建确认和取消退出提示。
 */
public final class WorkspaceStartupService {

    /** 用户取消后展示退出提示的时长。 */
    static final long CANCEL_EXIT_DELAY_MILLIS = 5_000L;

    private final WorkspaceStore workspaceStore;
    private final WorkspaceResolver workspaceResolver;
    private final BufferedReader input;
    private final PrintStream output;
    private final InterruptibleSleeper sleeper;

    /**
     * 创建工作区启动服务。
     *
     * @param workspaceStore    工作区持久化访问入口
     * @param workspaceResolver 当前启动目录解析器
     * @param input             用户确认输入
     * @param output            CLI 输出目标
     * @param sleeper           取消退出前的可中断等待器
     */
    public WorkspaceStartupService(
            WorkspaceStore workspaceStore,
            WorkspaceResolver workspaceResolver,
            BufferedReader input,
            PrintStream output,
            InterruptibleSleeper sleeper
    ) {
        this.workspaceStore = Objects.requireNonNull(workspaceStore, "workspaceStore");
        this.workspaceResolver = Objects.requireNonNull(workspaceResolver, "workspaceResolver");
        this.input = Objects.requireNonNull(input, "input");
        this.output = Objects.requireNonNull(output, "output");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    /**
     * 检查当前目录是否已是工作区；不存在时读取一次用户确认并决定是否创建。
     *
     * @param launchDirectory 执行 {@code wecode} 时的当前工作目录
     * @return 启动阶段的工作区确认结果
     */
    public WorkspaceStartupResult start(Path launchDirectory) {
        WorkspaceContext workspace = workspaceResolver.resolve(launchDirectory);
        String workspacePath = workspace.workspacePath().toString();

        // 已存在的工作区不需要询问用户，也不重复创建。
        if (workspaceStore.existsByAbsoluteRootPath(workspacePath)) {
            output.println("当前目录已是 WeCode 工作区：" + workspacePath);
            return WorkspaceStartupResult.EXISTS;
        }

        output.print("当前目录尚不是 WeCode 工作区，是否创建为工作区？[Y/N] ");
        output.flush();
        String answer = readAnswer();

        // 仅明确确认时允许持久化工作区；EOF、空白和无效输入均按取消处理。
        if (!isAffirmative(answer)) {
            cancelAndExit(answer);
            return WorkspaceStartupResult.CANCELLED;
        }

        try {
            workspaceStore.create(workspacePath);
            output.println("已创建 WeCode 工作区：" + workspacePath);
            return WorkspaceStartupResult.CREATED;
        } catch (RuntimeException exception) {
            // 查询与创建之间可能有另一进程完成创建；回查成功即按已有工作区继续。
            if (workspaceStore.existsByAbsoluteRootPath(workspacePath)) {
                output.println("当前目录已由其他 WeCode 进程创建为工作区：" + workspacePath);
                return WorkspaceStartupResult.EXISTS;
            }
            throw exception;
        }
    }

    /**
     * 从标准输入读取一次确认文本。
     *
     * @return 用户输入；标准输入结束时为 {@code null}
     */
    private String readAnswer() {
        try {
            return input.readLine();
        } catch (IOException exception) {
            // 输入流读取失败无法安全地视作用户取消，应显式暴露启动失败。
            throw new IllegalStateException("无法读取工作区创建确认输入", exception);
        }
    }

    /**
     * 判断用户输入是否为明确的创建确认。
     *
     * @param answer 用户输入文本，可为 {@code null}
     * @return 输入为 Y 或 yes（忽略大小写和首尾空白）时返回 {@code true}
     */
    private static boolean isAffirmative(String answer) {
        if (answer == null) {
            return false;
        }
        String normalized = answer.strip().toLowerCase(Locale.ROOT);
        return "y".equals(normalized) || "yes".equals(normalized);
    }

    /**
     * 输出取消原因，并等待固定时长后让调用方结束 CLI 进程。
     *
     * @param answer 用户原始输入；{@code null} 表示标准输入结束
     */
    private void cancelAndExit(String answer) {
        // 明确 N 与未确认输入采用不同文本，便于用户区分主动退出和输入未生效。
        if (isNegative(answer)) {
            output.println("已退出 WeCode，5 秒后关闭程序。");
        } else {
            output.println("未确认创建工作区，已退出 WeCode，5 秒后关闭程序。");
        }
        output.flush();

        try {
            sleeper.sleep(CANCEL_EXIT_DELAY_MILLIS);
        } catch (InterruptedException exception) {
            // 恢复中断信号，让上层运行环境仍可感知取消请求。
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 判断用户输入是否为明确拒绝。
     *
     * @param answer 用户输入文本，可为 {@code null}
     * @return 输入为 N 或 no（忽略大小写和首尾空白）时返回 {@code true}
     */
    private static boolean isNegative(String answer) {
        if (answer == null) {
            return false;
        }
        String normalized = answer.strip().toLowerCase(Locale.ROOT);
        return "n".equals(normalized) || "no".equals(normalized);
    }

    /** 可中断的等待抽象，供生产代码等待且供测试避免真实睡眠。 */
    @FunctionalInterface
    public interface InterruptibleSleeper {

        /**
         * 等待指定时长。
         *
         * @param millis 等待毫秒数
         * @throws InterruptedException 当前线程在等待期间被中断时抛出
         */
        void sleep(long millis) throws InterruptedException;
    }
}
