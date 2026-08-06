package org.wecode.cli.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证外部数据目录的配置校验与目录创建行为。
 */
class StoragePathResolverTest {

    /**
     * 显式配置的绝对路径应被原样规范化。
     */
    @Test
    void normalizesAbsoluteConfiguredRoot(@TempDir Path temporaryDirectory) {
        Path configured = temporaryDirectory.resolve("wecode").toAbsolutePath();

        Path actual = StoragePathResolver.normalizeConfiguredRoot(configured.toString(), "测试配置");

        assertEquals(configured.normalize(), actual);
    }

    /**
     * 相对路径会导致文件随工作目录漂移，必须在配置解析阶段拒绝。
     */
    @Test
    void rejectsRelativeConfiguredRoot() {
        assertThrows(
                IllegalArgumentException.class,
                () -> StoragePathResolver.normalizeConfiguredRoot("wecode-data", "测试配置")
        );
    }

    /**
     * 首次使用外部数据目录时，应递归创建目录并返回原路径。
     */
    @Test
    void createsMissingRootDirectory(@TempDir Path temporaryDirectory) {
        Path root = temporaryDirectory.resolve("nested").resolve("wecode").toAbsolutePath();

        Path actual = StoragePathResolver.ensureRootDirectory(root);

        assertEquals(root, actual);
        assertTrue(Files.isDirectory(root));
    }
}
