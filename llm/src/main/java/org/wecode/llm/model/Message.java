package org.wecode.llm.model;

import java.util.List;
import java.util.Objects;

/**
 * One turn in the chat history (flat OpenAI-style message).
 *
 * @param role        message role
 * @param content     text body; may be null for assistant messages that only call tools
 * @param toolCalls   assistant-only; empty for other roles
 * @param toolCallId  tool-only; id of the {@link ToolCall} this message answers
 */
public record Message(
        Role role,
        String content,
        List<ToolCall> toolCalls,
        String toolCallId
) {

    public Message {
        Objects.requireNonNull(role, "role");
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        validate(role, content, toolCalls, toolCallId);
    }

    public static Message system(String content) {
        requireNonBlankContent(content, "system");
        return new Message(Role.SYSTEM, content, List.of(), null);
    }

    public static Message user(String content) {
        requireNonBlankContent(content, "user");
        return new Message(Role.USER, content, List.of(), null);
    }

    public static Message assistant(String content, List<ToolCall> toolCalls) {
        List<ToolCall> calls = toolCalls == null ? List.of() : toolCalls;
        return new Message(Role.ASSISTANT, content, calls, null);
    }

    public static Message tool(String toolCallId, String content) {
        Objects.requireNonNull(toolCallId, "toolCallId");
        Objects.requireNonNull(content, "content");
        if (toolCallId.isBlank()) {
            throw new IllegalArgumentException("toolCallId must not be blank");
        }
        return new Message(Role.TOOL, content, List.of(), toolCallId);
    }

    private static void requireNonBlankContent(String content, String roleLabel) {
        Objects.requireNonNull(content, roleLabel + " content");
        if (content.isBlank()) {
            throw new IllegalArgumentException(roleLabel + " content must not be blank");
        }
    }

    private static void validate(
            Role role,
            String content,
            List<ToolCall> toolCalls,
            String toolCallId
    ) {
        switch (role) {
            case SYSTEM, USER -> {
                if (content == null || content.isBlank()) {
                    throw new IllegalArgumentException(role + " content must not be blank");
                }
                if (!toolCalls.isEmpty()) {
                    throw new IllegalArgumentException(role + " must not have toolCalls");
                }
                if (toolCallId != null) {
                    throw new IllegalArgumentException(role + " must not have toolCallId");
                }
            }
            case ASSISTANT -> {
                if (toolCallId != null) {
                    throw new IllegalArgumentException("ASSISTANT must not have toolCallId");
                }
            }
            case TOOL -> {
                if (content == null) {
                    throw new IllegalArgumentException("TOOL content must not be null");
                }
                if (!toolCalls.isEmpty()) {
                    throw new IllegalArgumentException("TOOL must not have toolCalls");
                }
                if (toolCallId == null || toolCallId.isBlank()) {
                    throw new IllegalArgumentException("TOOL toolCallId must not be blank");
                }
            }
        }
    }
}
