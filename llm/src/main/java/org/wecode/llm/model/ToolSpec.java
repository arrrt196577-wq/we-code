package org.wecode.llm.model;

import java.util.Objects;

/**
 * Tool description passed to the provider (name + description + JSON Schema parameters).
 *
 * @param name            tool name
 * @param description     human/model-facing description
 * @param parametersJson  JSON Schema object for arguments; use {@code "{\"type\":\"object\",\"properties\":{}}"} when none
 */
public record ToolSpec(String name, String description, String parametersJson) {

    public ToolSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(parametersJson, "parametersJson");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }

    public static ToolSpec of(String name, String description) {
        return new ToolSpec(name, description, "{\"type\":\"object\",\"properties\":{}}");
    }
}
