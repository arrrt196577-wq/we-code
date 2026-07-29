package org.wecode.tools.result;

import java.util.Objects;

/**
 * Outcome of executing one tool call.
 *
 * @param toolCallId  id of the originating tool call
 * @param name        tool name that was executed (or attempted)
 * @param content     observation text for the model (success output or error message)
 * @param error       true when execution failed but still produced an observation
 */
public record ToolResult(
        String toolCallId,
        String name,
        String content,
        boolean error
) {

    public ToolResult {
        Objects.requireNonNull(toolCallId, "toolCallId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(content, "content");
        if (toolCallId.isBlank()) {
            throw new IllegalArgumentException("toolCallId must not be blank");
        }
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }

    public static ToolResult ok(String toolCallId, String name, String content) {
        return new ToolResult(toolCallId, name, content, false);
    }

    public static ToolResult failed(String toolCallId, String name, String content) {
        return new ToolResult(toolCallId, name, content, true);
    }
}
