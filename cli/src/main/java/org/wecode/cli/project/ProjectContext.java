package org.wecode.cli.project;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 一次 CLI 启动解析出的项目上下文。
 *
 * @param launchDirectory  用户执行 {@code wecode} 时终端所在的目录
 * @param projectRoot      Agent 默认允许访问的项目根目录
 * @param workingDirectory 后续执行命令和解析相对路径时使用的工作目录
 * @param type             项目识别方式
 */
public record ProjectContext(
        Path launchDirectory,
        Path projectRoot,
        Path workingDirectory,
        ProjectType type
) {

    public ProjectContext {
        launchDirectory = normalize(launchDirectory, "launchDirectory");
        projectRoot = normalize(projectRoot, "projectRoot");
        workingDirectory = normalize(workingDirectory, "workingDirectory");
        type = Objects.requireNonNull(type, "type");
    }

    /**
     * 统一上下文中所有路径的表示，避免相对路径依赖 JVM 启动位置。
     *
     * @param path      待规范化路径
     * @param fieldName 参数名称，用于生成清晰的异常信息
     * @return 绝对且已规范化的路径
     */
    private static Path normalize(Path path, String fieldName) {
        return Objects.requireNonNull(path, fieldName).toAbsolutePath().normalize();
    }
}
