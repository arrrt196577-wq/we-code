package org.wecode.llm.model;

import java.util.Objects;

/**
 * A single function/tool invocation requested by the model.
 *
 * @param id             correlates with tool-role messages / ToolResult
 * @param name           tool name registered in the tool registry
 * @param argumentsJson  raw JSON object string; use {@code "{}"} when empty
 */
public record ToolCall(String id, String name, String argumentsJson) {

    public ToolCall {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(argumentsJson, "argumentsJson");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }
}
