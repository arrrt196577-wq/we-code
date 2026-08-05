package org.wecode.tools.spi;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 单次工具执行的上下文；一次 Agent 运行对应一个项目根目录。
 *
 * @param projectRoot 用户选定的项目根路径，也是工具允许访问的路径边界（绝对、已规范化）
 */
public record ToolContext(Path projectRoot) {

    public ToolContext {
        Objects.requireNonNull(projectRoot, "projectRoot");
        // 统一成绝对路径，避免相对路径在不同 cwd 下歧义
        projectRoot = projectRoot.toAbsolutePath().normalize();
    }

    /**
     * 由用户选择的目录构造上下文。
     *
     * @param projectRoot 项目根目录
     */
    public static ToolContext of(Path projectRoot) {
        return new ToolContext(projectRoot);
    }
}
