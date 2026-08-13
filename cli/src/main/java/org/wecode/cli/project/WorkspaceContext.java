package org.wecode.cli.project;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 一次 CLI 启动解析出的工作区上下文。
 *
 * @param workspacePath 用户执行 {@code wecode} 时所在的真实目录，也是工具访问边界
 * @param type          工作区目录类型
 */
public record WorkspaceContext(
        Path workspacePath,
        ProjectType type
) {

    public WorkspaceContext {
        workspacePath = normalize(workspacePath, "workspacePath");
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
