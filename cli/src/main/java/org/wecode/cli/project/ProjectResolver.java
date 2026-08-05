package org.wecode.cli.project;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 根据 CLI 启动目录识别当前项目。
 * <p>
 * 若目录位于 Git 仓库中，则使用 Git worktree 根目录作为项目根目录；否则将启动目录本身视为本地项目。
 */
public final class ProjectResolver {

    /** Git 命令的最长等待时间，避免 Git 配置异常时阻塞 CLI 启动。 */
    private static final long GIT_TIMEOUT_SECONDS = 5;

    /**
     * 解析当前命令启动目录对应的项目上下文。
     *
     * @param launchDirectory 用户执行 {@code wecode} 时的当前工作目录
     * @return 已解析的项目上下文
     */
    public ProjectContext resolve(Path launchDirectory) {
        Path realLaunchDirectory = requireExistingDirectory(launchDirectory, "launchDirectory");
        Path gitRoot = discoverGitRoot(realLaunchDirectory);

        // Git 仓库优先使用 worktree 根目录，确保同一仓库的子目录共享项目身份与文件边界。
        if (gitRoot != null) {
            return new ProjectContext(
                    realLaunchDirectory,
                    gitRoot,
                    realLaunchDirectory,
                    ProjectType.GIT_REPOSITORY
            );
        }

        // 非 Git 目录不应阻止用户使用 Agent，当前目录本身就是最小且安全的项目边界。
        return new ProjectContext(
                realLaunchDirectory,
                realLaunchDirectory,
                realLaunchDirectory,
                ProjectType.LOCAL_DIRECTORY
        );
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
     * 使用 Git 发现包含指定目录的最近 worktree 根目录。
     *
     * @param launchDirectory 已校验的真实启动目录
     * @return Git 根目录；Git 不存在、当前目录不在仓库内或探测失败时返回 {@code null}
     */
    private static Path discoverGitRoot(Path launchDirectory) {
        Process process;
        try {
            process = new ProcessBuilder(
                    "git",
                    "-C",
                    launchDirectory.toString(),
                    "rev-parse",
                    "--show-toplevel"
            ).redirectErrorStream(true).start();
        } catch (IOException e) {
            // 未安装 Git 或无法启动 Git 时，降级为普通本地目录，保证基础 CLI 仍可使用。
            return null;
        }

        try {
            // Git 项目发现只应是短命令；超时后销毁进程并按非 Git 目录处理。
            if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }

            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            // 非零退出码代表当前目录不在 Git 仓库中，或 Git 无法解析该目录。
            if (process.exitValue() != 0 || output.isBlank()) {
                return null;
            }
            return requireExistingDirectory(Path.of(output), "git project root");
        } catch (InterruptedException e) {
            // 中断必须恢复，避免上层任务取消信号被吞掉。
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while discovering Git project root", e);
        } catch (IOException | IllegalArgumentException e) {
            // Git 输出不是可用目录时，按本地目录降级，避免启动流程被外部工具异常阻断。
            return null;
        }
    }
}
