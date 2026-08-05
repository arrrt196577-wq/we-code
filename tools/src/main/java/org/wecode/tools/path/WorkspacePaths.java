package org.wecode.tools.path;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 项目路径边界校验：将用户传入路径解析为绝对路径，并保证落点在项目根目录内。
 * <p>
 * 仅做 normalize 级校验，不跟随符号链接（不调用 {@code toRealPath}）。
 */
public final class WorkspacePaths {

    private WorkspacePaths() {
    }

    /**
     * 解析路径并校验必须落在项目根目录之内（含根目录本身）。
     *
     * @param projectRoot   项目根目录
     * @param rawPath       用户传入路径（相对或绝对）
     * @return 落在项目根目录内的绝对、已规范化路径
     * @throws IllegalArgumentException 参数非法或路径逃出项目根目录
     */
    public static Path resolveInside(Path projectRoot, String rawPath) {
        Objects.requireNonNull(projectRoot, "projectRoot");
        // 空路径无法解析
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("path is required and must be a non-blank string");
        }

        Path root = projectRoot.toAbsolutePath().normalize();
        Path input = Path.of(rawPath.trim());
        // 相对路径拼到项目根目录；绝对路径直接规范化后再做越界检查
        Path resolved = input.isAbsolute()
                ? input.normalize()
                : root.resolve(input).normalize();

        // 落点必须等于 root 或其子路径
        if (!isInside(root, resolved)) {
            throw new IllegalArgumentException(
                    "Path escapes project root: " + rawPath.trim()
                            + " (must stay under project root)"
            );
        }
        return resolved;
    }

    /**
     * 判断 candidate 是否等于 root 或位于 root 之下。
     * 使用 relativize，避免 {@code startsWith} 把 {@code .../ws2} 误判为 {@code .../ws} 的子路径。
     */
    private static boolean isInside(Path root, Path candidate) {
        // 正好是项目根目录
        if (candidate.equals(root)) {
            return true;
        }
        try {
            Path relative = root.relativize(candidate);
            // 首段为 ".." 表示向上逃出；空相对路径表示同一路径（已在 equals 覆盖）
            if (relative.getNameCount() == 0) {
                return true;
            }
            return !relative.getName(0).toString().equals("..");
        } catch (IllegalArgumentException e) {
            // 不同根（如 Windows 跨盘符）无法 relativize，视为越界
            return false;
        }
    }
}
