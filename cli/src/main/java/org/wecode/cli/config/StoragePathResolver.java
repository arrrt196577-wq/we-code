package org.wecode.cli.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * 解析并准备 WeCode 的外部数据根目录。
 * <p>
 * 目录优先级为：环境变量 {@code WECODE_HOME}、{@code wecode.yml} 的 {@code storage.root}、
 * Windows 默认目录 {@code %LOCALAPPDATA%\\wecode}。其他模块应复用本类获取根目录，
 * 不要自行拼接用户目录或将运行期数据写入项目仓库。
 */
public final class StoragePathResolver {

    /** 用于覆盖外部数据根目录的环境变量名称。 */
    public static final String STORAGE_HOME_ENV = "WECODE_HOME";

    private StoragePathResolver() {
    }

    /**
     * 按既定优先级解析外部数据根目录，但不创建目录。
     *
     * @param storageConfig YAML 中读取到的存储配置，可为空
     * @return 已规范化的绝对路径
     */
    public static Path resolveRoot(StorageConfig storageConfig) {

        // C:\Users\{user}\AppData\Local
        String environmentRoot = System.getenv(STORAGE_HOME_ENV);

        // 环境变量用于临时切换目录，优先级高于配置文件。
        if (!isBlank(environmentRoot)) {
            return normalizeConfiguredRoot(environmentRoot, "环境变量 " + STORAGE_HOME_ENV);
        }

        String configuredRoot = storageConfig == null ? null : storageConfig.root();
        // YAML 显式指定目录时，使用该目录替代系统默认目录。
        if (!isBlank(configuredRoot)) {
            return normalizeConfiguredRoot(configuredRoot, "配置项 storage.root");
        }

        // 未配置时，回退到当前 Windows 用户的本地应用数据目录。
        return defaultRoot();
    }

    /**
     * 推导当前用户默认的 WeCode 外部数据根目录。
     *
     * @return 默认目录 {@code %LOCALAPPDATA%\\wecode}；环境变量缺失时由 {@code user.home} 推导
     */
    public static Path defaultRoot() {
        String localAppData = System.getenv("LOCALAPPDATA");

        // Windows 已提供实际 Local AppData 目录时，优先使用该系统配置。
        if (!isBlank(localAppData)) {
            return normalizeConfiguredRoot(localAppData + java.io.File.separator + "wecode", "环境变量 LOCALAPPDATA");
        }

        String userHome = System.getProperty("user.home");
        // LOCALAPPDATA 缺失时，按 Windows 用户主目录的标准层级推导。
        if (!isBlank(userHome)) {
            return Path.of(userHome, "AppData", "Local", "wecode")
                    .toAbsolutePath()
                    .normalize();
        }

        // 无法确定用户目录时，禁止静默写入当前项目或进程工作目录。
        throw new IllegalStateException("无法确定 WeCode 外部数据目录：LOCALAPPDATA 和 user.home 均不可用");
    }

    /**
     * 校验并规范化调用方提供的外部数据根目录。
     *
     * @param rawPath 原始目录文本
     * @param source  路径来源，用于错误提示
     * @return 规范化后的绝对目录路径
     */
    public static Path normalizeConfiguredRoot(String rawPath, String source) {
        try {
            Path path = Path.of(rawPath.trim());
            // 配置目录必须绝对化，避免运行期文件随启动目录变化而落入仓库。
            if (!path.isAbsolute()) {
                throw new IllegalArgumentException(source + " 必须是绝对路径: " + rawPath);
            }
            return path.normalize();
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException(source + " 不是合法路径: " + rawPath, e);
        }
    }

    /**
     * 创建外部数据根目录；目录已存在时验证其确实为目录。
     *
     * @param root 已解析的外部数据根目录
     * @return 已创建且可作为根目录使用的路径
     */
    public static Path ensureRootDirectory(Path root) {
        if (root == null || !root.isAbsolute()) {
            throw new IllegalArgumentException("外部数据根目录必须是绝对路径");
        }

        try {
            // 目标已存在但不是目录时，不能将运行期数据写入普通文件。
            if (Files.exists(root) && !Files.isDirectory(root)) {
                throw new IllegalStateException("外部数据根目录不是目录: " + root);
            }
            Files.createDirectories(root);
            return root;
        } catch (IOException e) {
            throw new IllegalStateException("无法创建 WeCode 外部数据目录: " + root, e);
        }
    }

    /**
     * 判断文本是否为空白。
     *
     * @param value 待判断文本
     * @return 文本为 {@code null} 或仅包含空白时返回 {@code true}
     */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
