package org.wecode.tools.registry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.wecode.llm.model.ToolSpec;
import org.wecode.tools.impl.ReadTool;
import org.wecode.tools.result.ToolResult;
import org.wecode.tools.spi.ToolContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ToolRegistry} 冒烟：注册 Read、listSpecs、execute 成功与未知工具 failed。
 */
class ToolRegistryTest {

    @TempDir
    Path workspace;

    private ToolRegistry registry;
    private ToolContext context;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry();
        registry.register(new ReadTool());
        context = ToolContext.of(workspace);
    }

    @Test
    void listSpecsIncludesRead() {
        List<ToolSpec> specs = registry.listSpecs();

        assertEquals(1, specs.size());
        assertEquals(ReadTool.NAME, specs.getFirst().name());
        assertFalse(specs.getFirst().description().isBlank());
        assertTrue(specs.getFirst().parametersJson().contains("\"path\""));
    }

    @Test
    void executeReadsTempFile() throws Exception {
        Files.writeString(workspace.resolve("note.txt"), "hello registry\n");

        ToolResult result = registry.execute(
                "call-1",
                ReadTool.NAME,
                "{\"path\":\"note.txt\"}",
                context
        );

        assertFalse(result.error());
        assertEquals("call-1", result.toolCallId());
        assertEquals(ReadTool.NAME, result.name());
        assertTrue(result.content().contains("1|hello registry"));
    }

    @Test
    void unknownToolReturnsFailedWithoutThrowing() {
        ToolResult result = registry.execute(
                "call-missing",
                "NotARealTool",
                "{}",
                context
        );

        assertTrue(result.error());
        assertEquals("call-missing", result.toolCallId());
        assertEquals("NotARealTool", result.name());
        assertTrue(result.content().contains("Unknown tool"));
    }

    @Test
    void duplicateRegisterRejected() {
        assertThrows(IllegalArgumentException.class, () -> registry.register(new ReadTool()));
    }
}
