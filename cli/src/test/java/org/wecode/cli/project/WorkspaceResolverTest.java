package org.wecode.cli.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link WorkspaceResolver} 的工作区路径解析测试。
 */
class WorkspaceResolverTest {

    private final WorkspaceResolver resolver = new WorkspaceResolver();

    @TempDir
    Path temporaryDirectory;

    /** 验证非 Git 目录会以执行命令的真实目录作为工作区路径。 */
    @Test
    void resolveUsesLaunchDirectoryForNonGitDirectory() throws IOException {
        Path launchDirectory = Files.createDirectories(temporaryDirectory.resolve("plain-project").resolve("src"));

        WorkspaceContext context = resolver.resolve(launchDirectory);

        assertEquals(launchDirectory.toRealPath(), context.workspacePath());
        assertEquals(ProjectType.LOCAL_DIRECTORY, context.type());
    }

    /** 验证不存在的启动目录会在执行 Git 探测前被拒绝。 */
    @Test
    void resolveRejectsMissingLaunchDirectory() {
        Path missingDirectory = temporaryDirectory.resolve("missing");

        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(missingDirectory));
    }

    /** 验证从 Git 仓库子目录启动时，工作区路径仍是执行命令的目录。 */
    @Test
    void resolveKeepsLaunchDirectoryForNestedGitDirectory() throws IOException, InterruptedException {
        // 测试环境未安装 Git 时跳过；生产代码会自动降级为本地目录。
        Assumptions.assumeTrue(isGitAvailable(), "Git is required for this test");
        Path repositoryRoot = Files.createDirectories(temporaryDirectory.resolve("repository"));
        runGit(repositoryRoot, "init");
        Path launchDirectory = Files.createDirectories(repositoryRoot.resolve("module").resolve("src"));

        WorkspaceContext context = resolver.resolve(launchDirectory);

        assertEquals(launchDirectory.toRealPath(), context.workspacePath());
        assertEquals(ProjectType.GIT_REPOSITORY, context.type());
    }

    /**
     * 检测当前测试环境是否可调用 Git。
     *
     * @return Git 可执行时返回 true
     */
    private static boolean isGitAvailable() throws IOException, InterruptedException {
        Process process = new ProcessBuilder("git", "--version").redirectErrorStream(true).start();
        // Git 版本查询超时视为不可用，防止测试环境异常阻塞。
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return false;
        }
        return process.exitValue() == 0;
    }

    /**
     * 在指定目录执行 Git 命令，并在失败时输出命令结果。
     *
     * @param directory Git 命令执行目录
     * @param arguments Git 子命令及参数
     */
    private static void runGit(Path directory, String... arguments) throws IOException, InterruptedException {
        String[] command = new String[arguments.length + 3];
        command[0] = "git";
        command[1] = "-C";
        command[2] = directory.toString();
        System.arraycopy(arguments, 0, command, 3, arguments.length);

        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        // Git 初始化应快速完成；超时则销毁进程并让测试失败。
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Git command timed out");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        // 非零退出码表示测试前提未准备成功，直接给出 Git 输出便于定位。
        if (process.exitValue() != 0) {
            throw new AssertionError("Git command failed: " + output);
        }
    }
}
