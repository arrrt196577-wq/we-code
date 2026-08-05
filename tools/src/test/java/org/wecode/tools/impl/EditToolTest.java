package org.wecode.tools.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wecode.tools.result.ToolResult;
import org.wecode.tools.spi.ToolContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EditTool} 单元测试：唯一替换、全量替换、创建与失败路径。
 */
class EditToolTest {

    @TempDir
    Path workspace;

    private EditTool tool;
    private ToolContext context;

    @BeforeEach
    void setUp() {
        tool = new EditTool();
        context = ToolContext.of(workspace);
    }

    @Test
    void replacesUniqueMatch() throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "hello world\n");

        ToolResult result = tool.execute(
                context,
                "call-1",
                "{\"path\":\"a.txt\",\"old_string\":\"world\",\"new_string\":\"we-code\"}"
        );

        assertFalse(result.error());
        assertTrue(result.content().contains("1 replacement"));
        assertEquals("hello we-code\n", Files.readString(workspace.resolve("a.txt")));
    }

    @Test
    void failsWhenNoMatch() throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "hello\n");

        ToolResult result = tool.execute(
                context,
                "call-2",
                "{\"path\":\"a.txt\",\"old_string\":\"missing\",\"new_string\":\"x\"}"
        );

        assertTrue(result.error());
        assertTrue(result.content().contains("not found"));
        assertEquals("hello\n", Files.readString(workspace.resolve("a.txt")));
    }

    @Test
    void failsWhenMultipleMatchesWithoutReplaceAll() throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "aa aa aa\n");

        ToolResult result = tool.execute(
                context,
                "call-3",
                "{\"path\":\"a.txt\",\"old_string\":\"aa\",\"new_string\":\"bb\"}"
        );

        assertTrue(result.error());
        assertTrue(result.content().contains("matched 3 times"));
        assertEquals("aa aa aa\n", Files.readString(workspace.resolve("a.txt")));
    }

    @Test
    void replaceAllSucceeds() throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "aa aa aa\n");

        ToolResult result = tool.execute(
                context,
                "call-4",
                "{\"path\":\"a.txt\",\"old_string\":\"aa\",\"new_string\":\"bb\",\"replace_all\":true}"
        );

        assertFalse(result.error());
        assertTrue(result.content().contains("3 replacements"));
        assertEquals("bb bb bb\n", Files.readString(workspace.resolve("a.txt")));
    }

    @Test
    void createsNewFileWithEmptyOldStringIncludingParents() throws Exception {
        ToolResult result = tool.execute(
                context,
                "call-5",
                "{\"path\":\"sub/dir/new.txt\",\"old_string\":\"\",\"new_string\":\"created\\n\"}"
        );

        assertFalse(result.error());
        assertTrue(result.content().startsWith("Created "));
        Path created = workspace.resolve("sub/dir/new.txt");
        assertTrue(Files.isRegularFile(created));
        assertEquals("created\n", Files.readString(created, StandardCharsets.UTF_8));
    }

    @Test
    void failsEmptyOldStringWhenFileExists() throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "keep\n");

        ToolResult result = tool.execute(
                context,
                "call-6",
                "{\"path\":\"a.txt\",\"old_string\":\"\",\"new_string\":\"overwrite\"}"
        );

        assertTrue(result.error());
        assertTrue(result.content().contains("already exists"));
        assertEquals("keep\n", Files.readString(workspace.resolve("a.txt")));
    }

    @Test
    void failsWhenPathEscapesWorkspace() {
        ToolResult result = tool.execute(
                context,
                "call-7",
                "{\"path\":\"../outside.txt\",\"old_string\":\"a\",\"new_string\":\"b\"}"
        );

        assertTrue(result.error());
        assertTrue(result.content().contains("escapes project root"));
    }

    @Test
    void failsWhenOldEqualsNew() throws Exception {
        Files.writeString(workspace.resolve("a.txt"), "same\n");

        ToolResult result = tool.execute(
                context,
                "call-8",
                "{\"path\":\"a.txt\",\"old_string\":\"same\",\"new_string\":\"same\"}"
        );

        assertTrue(result.error());
        assertTrue(result.content().contains("identical"));
        assertEquals("same\n", Files.readString(workspace.resolve("a.txt")));
    }
}
