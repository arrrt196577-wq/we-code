package org.wecode.cli.project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 根据 CLI 启动目录识别当前工作区。
 * <p>
 * 工作区路径始终是用户执行 {@code wecode} 时的真实目录；Git 探测只标记目录类型，
 * 不会将路径提升到 Git worktree 根目录。
 */
public final class WorkspaceResolver {

    /** Git 命令的最长等待时间，避免 Git 配置异常时阻塞 CLI 启动。 */
    private static final long GIT_TIMEOUT_SECONDS = 5;

    /**
     * 解析当前命令启动目录对应的项目上下文。
     *
     * @param launchDirectory 用户执行 {@code wecode} 时的当前工作目录
     * @return 已解析的项目上下文
     */
    public WorkspaceContext resolve(Path launchDirectory) {
        Path workspacePath = requireExistingDirectory(launchDirectory, "launchDirectory");
        ProjectType type = isInsideGitRepository(workspacePath)
                ? ProjectType.GIT_REPOSITORY
                : ProjectType.LOCAL_DIRECTORY;

        // 当前阶段不赋予路径其他含义：执行命令的真实目录就是唯一工作区路径。
        return new WorkspaceContext(workspacePath, type);
    }

    /**
     * 验证路径存在且为目录，并解析符号链接后的真实路径。
     *
     * @param directory 待验证目录
     * @param fieldName 参数名称，用于生成清晰的异常信息
     * @return 真实目录路径
     */
    private static Path requireExistingDirectory(Path directory, String fieldName) {
        Objects.requireNonNull(directory, fieldName);
        Path normalized = directory.toAbsolutePath().normalize();
        // 启动目录不存在或不是目录时，后续 Git 和文件工具都无法获得可靠语义，因此立即失败。
        if (!Files.isDirectory(normalized)) {
            throw new IllegalArgumentException(fieldName + " is not an existing directory: " + normalized);
        }
        try {
            return normalized.toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to resolve " + fieldName + ": " + normalized, e);
        }
    }

    /**
     * 使用 Git 判断指定工作区目录是否位于仓库中。
     *
     * @param workspacePath 已校验的真实工作区目录
     * @return 当前目录位于 Git worktree 中时返回 {@code true}
     */
    private static boolean isInsideGitRepository(Path workspacePath) {
        Process process;
        try {
            process = new ProcessBuilder(
                    "git",
                    "-C",
                    workspacePath.toString(),
                    "rev-parse",
                    "--is-inside-work-tree"
            ).redirectErrorStream(true).start();
        } catch (IOException e) {
            // 未安装 Git 或无法启动 Git 时，降级为普通本地目录，保证基础 CLI 仍可使用。
            return false;
        }

        try {
            // Git 类型探测只应是短命令；超时后销毁进程并按普通目录处理。
            if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }

            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            // 只读取 Git 返回的布尔结果，绝不使用 Git 输出派生工作区路径。
            return process.exitValue() == 0 && "true".equalsIgnoreCase(output);
        } catch (InterruptedException e) {
            // 中断必须恢复，避免上层任务取消信号被吞掉。
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while detecting Git workspace type", e);
        } catch (IOException e) {
            // 无法读取 Git 输出时按普通目录降级，避免外部工具异常阻断启动。
            return false;
        }
    }
}
