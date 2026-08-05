package org.wecode.tools.registry;

import org.wecode.llm.model.ToolSpec;
import org.wecode.tools.result.ToolResult;
import org.wecode.tools.spi.Tool;
import org.wecode.tools.spi.ToolContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 工具注册表：按名称注册、列出 {@link ToolSpec}、按 tool call 执行。
 * <p>
 * 未知工具名返回 {@link ToolResult#failed}，不抛异常打断 Agent Loop。
 */
public final class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    /**
     * 按 {@link Tool#name()} 注册；同名拒绝。
     *
     * @param tool 工具实现
     */
    public void register(Tool tool) {
        Objects.requireNonNull(tool, "tool");
        String name = tool.name();
        // 同名覆盖会让模型与执行面不一致，直接拒绝
        if (tools.containsKey(name)) {
            throw new IllegalArgumentException("tool already registered: " + name);
        }
        tools.put(name, tool);
    }

    /**
     * 按名称查找工具；未注册返回 {@code null}。
     *
     * @param name 工具名
     * @return 工具，或 null
     */
    public Tool get(String name) {
        if (name == null) {
            return null;
        }
        return tools.get(name);
    }

    /**
     * 列出已注册工具的 LLM 规格（保持注册顺序）。
     *
     * @return ToolSpec 列表
     */
    public List<ToolSpec> listSpecs() {
        List<ToolSpec> specs = new ArrayList<>(tools.size());
        for (Tool tool : tools.values()) {
            specs.add(new ToolSpec(tool.name(), tool.description(), tool.parametersSchema()));
        }
        return List.copyOf(specs);
    }

    /**
     * 执行一次 tool call。
     *
     * @param toolCallId    本次调用 id
     * @param name          工具名
     * @param argumentsJson 参数 JSON
     * @param context       执行上下文（含项目根目录）
     * @return 成功或失败 observation；未知工具也返回 failed
     */
    public ToolResult execute(
            String toolCallId,
            String name,
            String argumentsJson,
            ToolContext context
    ) {
        Objects.requireNonNull(toolCallId, "toolCallId");
        Objects.requireNonNull(context, "context");
        // 名称缺失时仍返回 failed，避免打断 Loop
        if (name == null || name.isBlank()) {
            return ToolResult.failed(
                    toolCallId,
                    name == null ? "unknown" : name,
                    "Unknown tool: blank name"
            );
        }
        Tool tool = tools.get(name);
        // 模型 invent 了未注册工具名
        if (tool == null) {
            return ToolResult.failed(toolCallId, name, "Unknown tool: " + name);
        }
        String args = argumentsJson == null ? "{}" : argumentsJson;
        return tool.execute(context, toolCallId, args);
    }
}
