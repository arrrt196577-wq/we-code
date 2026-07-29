package org.wecode.tools.spi;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 单次工具执行的上下文；一次 Agent 运行对应一个 workspace 根目录。
 *
 * @param workspaceRoot 用户选定的工作区根路径（绝对、已规范化）
 */
public record ToolContext(Path workspaceRoot) {

    public ToolContext {
        Objects.requireNonNull(workspaceRoot, "workspaceRoot");
        // 统一成绝对路径，避免相对路径在不同 cwd 下歧义
        workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
    }

    /**
     * 由用户选择的目录构造上下文。
     *
     * @param workspaceRoot 工作区根目录
     */
    public static ToolContext of(Path workspaceRoot) {
        return new ToolContext(workspaceRoot);
    }
}
