package org.wecode.tools.spi;

import org.wecode.tools.result.ToolResult;

/**
 * Agent 工具契约：一个实现类对应一个工具。
 */
public interface Tool {

    /**
     * 工具名（暴露给模型的 function name）。
     */
    String name();

    /**
     * 工具用途说明（暴露给模型）。
     */
    String description();

    /**
     * 参数的 JSON Schema 对象字符串，供 {@code ToolSpec} 使用。
     */
    String parametersSchema();

    /**
     * 执行工具调用。
     *
     * @param context       含 workspace 根目录的执行上下文
     * @param toolCallId    本次 tool call id，写入 {@link ToolResult}
     * @param argumentsJson 模型传入的参数 JSON 对象字符串
     * @return 成功或失败的 observation；业务失败应返回 failed，避免打断 Agent Loop
     */
    ToolResult execute(ToolContext context, String toolCallId, String argumentsJson);
}
