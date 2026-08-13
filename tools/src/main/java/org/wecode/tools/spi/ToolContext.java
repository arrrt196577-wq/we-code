package org.wecode.tools.spi;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 单次工具执行的上下文；一次 Agent 运行对应一个工作区路径。
 *
 * @param workspacePath 用户执行 {@code wecode} 的目录，也是工具允许访问的路径边界
 */
public record ToolContext(Path workspacePath) {

    public ToolContext {
        Objects.requireNonNull(workspacePath, "workspacePath");
        // 统一成绝对路径，避免相对路径在不同 cwd 下歧义
        workspacePath = workspacePath.toAbsolutePath().normalize();
    }

    /**
     * 由用户选择的目录构造上下文。
     *
     * @param workspacePath 工作区路径
     */
    public static ToolContext of(Path workspacePath) {
        return new ToolContext(workspacePath);
    }
}
