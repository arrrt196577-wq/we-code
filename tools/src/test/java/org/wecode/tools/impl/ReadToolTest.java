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
 * {@link ReadTool} 单元测试：在临时 workspace 内验证读文件主路径。
 */
class ReadToolTest {

    @TempDir
    Path workspace;

    private ReadTool tool;
    private ToolContext context;

    @BeforeEach
    void setUp() {
        tool = new ReadTool();
        context = ToolContext.of(workspace);
    }

    @Test
    void readsTextFileWithLineNumbers() throws Exception {
        Files.writeString(workspace.resolve("hello.txt"), "alpha\nbeta\ngamma\n");

        ToolResult result = tool.execute(context, "call-1", "{\"path\":\"hello.txt\"}");

        assertFalse(result.error());
        assertEquals(ReadTool.NAME, result.name());
        assertEquals("call-1", result.toolCallId());
        assertTrue(result.content().contains("1|alpha"));
        assertTrue(result.content().contains("2|beta"));
        assertTrue(result.content().contains("3|gamma"));
    }

    @Test
    void readsWindowWithOffsetAndLimit() throws Exception {
        Files.writeString(workspace.resolve("lines.txt"), "a\nb\nc\nd\ne\n");

        ToolResult result = tool.execute(
                context,
                "call-2",
                "{\"path\":\"lines.txt\",\"offset\":2,\"limit\":2}"
        );

        assertFalse(result.error());
        assertTrue(result.content().startsWith("lines 2-3 of 5"));
        assertTrue(result.content().contains("2|b"));
        assertTrue(result.content().contains("3|c"));
        assertFalse(result.content().contains("1|a"));
        assertFalse(result.content().contains("4|d"));
    }

    @Test
    void returnsEmptyFileMarker() throws Exception {
        Files.writeString(workspace.resolve("empty.txt"), "");

        ToolResult result = tool.execute(context, "call-3", "{\"path\":\"empty.txt\"}");

        assertFalse(result.error());
        assertEquals("(empty file)", result.content());
    }

    @Test
    void failsWhenFileMissing() {
        ToolResult result = tool.execute(context, "call-4", "{\"path\":\"missing.txt\"}");

        assertTrue(result.error());
        assertTrue(result.content().contains("File not found"));
    }

    @Test
    void failsWhenPathBlank() {
        ToolResult result = tool.execute(context, "call-5", "{\"path\":\"  \"}");

        assertTrue(result.error());
        assertTrue(result.content().contains("path is required"));
    }

    @Test
    void failsOnBinaryContent() throws Exception {
        // 写入含 NUL 的内容，触发二进制拒绝逻辑
        Files.write(workspace.resolve("bin.dat"), new byte[]{'a', 0, 'b'});

        ToolResult result = tool.execute(context, "call-6", "{\"path\":\"bin.dat\"}");

        assertTrue(result.error());
        assertTrue(result.content().contains("binary"));
    }

    @Test
    void failsWhenWholeFileExceedsSizeLimit() throws Exception {
        // 超过硬上限且未指定 offset/limit，应直接失败
        byte[] payload = new byte[ReadTool.MAX_FILE_BYTES + 1];
        Files.write(workspace.resolve("huge.txt"), payload);

        ToolResult result = tool.execute(context, "call-7", "{\"path\":\"huge.txt\"}");

        assertTrue(result.error());
        assertTrue(result.content().contains("File too large"));
        assertTrue(result.content().contains("offset/limit"));
    }

    @Test
    void resolvesRelativePathUnderWorkspace() throws Exception {
        Path nested = workspace.resolve("src/Main.java");
        Files.createDirectories(nested.getParent());
        Files.writeString(nested, "class Main {}\n", StandardCharsets.UTF_8);

        ToolResult result = tool.execute(context, "call-8", "{\"path\":\"src/Main.java\"}");

        assertFalse(result.error());
        assertTrue(result.content().contains("1|class Main {}"));
    }

    @Test
    void failsWhenRelativePathEscapesWorkspace() throws Exception {
        Path outside = workspace.getParent().resolve("outside-secret.txt");
        Files.writeString(outside, "secret\n", StandardCharsets.UTF_8);

        ToolResult result = tool.execute(context, "call-9", "{\"path\":\"../outside-secret.txt\"}");

        assertTrue(result.error());
        assertTrue(result.content().contains("Path escapes workspace"));
    }

    @Test
    void failsWhenAbsolutePathEscapesWorkspace() throws Exception {
        Path outside = workspace.getParent().resolve("abs-secret.txt");
        Files.writeString(outside, "secret\n", StandardCharsets.UTF_8);
        // JSON 中反斜杠需转义；toUri 风格用正斜杠更稳妥，这里用 toString 再转义
        String abs = outside.toAbsolutePath().normalize().toString().replace("\\", "\\\\");

        ToolResult result = tool.execute(context, "call-10", "{\"path\":\"" + abs + "\"}");

        assertTrue(result.error());
        assertTrue(result.content().contains("Path escapes workspace"));
    }
}
