package org.wecode.tools.path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WorkspacePaths} 单元测试：合法解析与路径逃逸拒绝。
 */
class WorkspacePathsTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesRelativePathUnderWorkspace() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("ws"));
        Path expected = workspace.resolve("a").resolve("b.txt").normalize();

        Path resolved = WorkspacePaths.resolveInside(workspace, "a/b.txt");

        assertEquals(expected.toAbsolutePath().normalize(), resolved);
    }

    @Test
    void resolvesDotAsWorkspaceRoot() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("ws"));

        Path resolved = WorkspacePaths.resolveInside(workspace, ".");

        assertEquals(workspace.toAbsolutePath().normalize(), resolved);
    }

    @Test
    void rejectsParentTraversal() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("ws"));

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> WorkspacePaths.resolveInside(workspace, "../outside")
        );
        assertTrue(ex.getMessage().contains("Path escapes workspace"));
    }

    @Test
    void rejectsNestedParentTraversal() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("ws"));

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> WorkspacePaths.resolveInside(workspace, "sub/../../outside")
        );
        assertTrue(ex.getMessage().contains("Path escapes workspace"));
    }

    @Test
    void acceptsAbsolutePathInsideWorkspace() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("ws"));
        Path inside = workspace.resolve("inside.txt").toAbsolutePath().normalize();

        Path resolved = WorkspacePaths.resolveInside(workspace, inside.toString());

        assertEquals(inside, resolved);
    }

    @Test
    void rejectsAbsolutePathOutsideWorkspace() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("ws"));
        Path outside = Files.createDirectories(tempDir.resolve("outside")).resolve("secret.txt");

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> WorkspacePaths.resolveInside(workspace, outside.toAbsolutePath().toString())
        );
        assertTrue(ex.getMessage().contains("Path escapes workspace"));
    }

    @Test
    void rejectsSiblingPrefixPath() throws Exception {
        // 避免 startsWith 把 .../ws2 误判为 .../ws 的子路径
        Path workspace = Files.createDirectories(tempDir.resolve("ws"));
        Path sibling = Files.createDirectories(tempDir.resolve("ws2")).resolve("x");

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> WorkspacePaths.resolveInside(workspace, sibling.toAbsolutePath().toString())
        );
        assertTrue(ex.getMessage().contains("Path escapes workspace"));
    }

    @Test
    void rejectsBlankPath() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("ws"));

        assertThrows(IllegalArgumentException.class, () -> WorkspacePaths.resolveInside(workspace, "  "));
    }
}
