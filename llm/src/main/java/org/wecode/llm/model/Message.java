package org.wecode.llm.model;

import java.util.List;
import java.util.Objects;

/**
 * 一条扁平化的会话消息，结构与 OpenAI Chat Completions 的角色模型保持一致。
 *
 * @param role             消息角色
 * @param content          文本内容；仅含工具调用的 assistant 消息可为 {@code null}
 * @param toolCalls        assistant 发起的工具调用；其他角色必须为空
 * @param toolCallId       tool 消息关联的工具调用 ID；其他角色必须为空
 * @param reasoningContent assistant 的思考内容；用于需要回传该字段的兼容 Provider
 */
public record Message(
        Role role,
        String content,
        List<ToolCall> toolCalls,
        String toolCallId,
        String reasoningContent
) {

    public Message {
        Objects.requireNonNull(role, "role");
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        validate(role, content, toolCalls, toolCallId, reasoningContent);
    }

    /**
     * 创建系统提示消息。
     *
     * @param content 系统提示文本
     * @return 系统消息
     */
    public static Message system(String content) {
        requireNonBlankContent(content, "system");
        return new Message(Role.SYSTEM, content, List.of(), null, null);
    }

    /**
     * 创建用户消息。
     *
     * @param content 用户输入文本
     * @return 用户消息
     */
    public static Message user(String content) {
        requireNonBlankContent(content, "user");
        return new Message(Role.USER, content, List.of(), null, null);
    }

    /**
     * 创建不携带思考内容的 assistant 消息。
     *
     * @param content   assistant 正文；仅调用工具时可为 {@code null}
     * @param toolCalls assistant 请求的工具调用
     * @return assistant 消息
     */
    public static Message assistant(String content, List<ToolCall> toolCalls) {
        return assistant(content, toolCalls, null);
    }

    /**
     * 创建可在后续请求中回传思考内容的 assistant 消息。
     *
     * @param content          assistant 正文；仅调用工具时可为 {@code null}
     * @param toolCalls        assistant 请求的工具调用
     * @param reasoningContent Provider 返回的思考内容；可为 {@code null}
     * @return assistant 消息
     */
    public static Message assistant(String content, List<ToolCall> toolCalls, String reasoningContent) {
        List<ToolCall> calls = toolCalls == null ? List.of() : toolCalls;
        return new Message(Role.ASSISTANT, content, calls, null, reasoningContent);
    }

    /**
     * 创建工具执行结果消息。
     *
     * @param toolCallId 要响应的工具调用 ID
     * @param content    工具执行结果文本
     * @return 工具消息
     */
    public static Message tool(String toolCallId, String content) {
        Objects.requireNonNull(toolCallId, "toolCallId");
        Objects.requireNonNull(content, "content");
        // 空工具调用 ID 无法与模型返回的调用匹配。
        if (toolCallId.isBlank()) {
            throw new IllegalArgumentException("toolCallId must not be blank");
        }
        return new Message(Role.TOOL, content, List.of(), toolCallId, null);
    }

    /**
     * 校验系统和用户消息必须具备正文。
     *
     * @param content   待校验内容
     * @param roleLabel 角色名称，仅用于错误信息
     */
    private static void requireNonBlankContent(String content, String roleLabel) {
        Objects.requireNonNull(content, roleLabel + " content");
        // 系统和用户消息的空正文没有可执行语义。
        if (content.isBlank()) {
            throw new IllegalArgumentException(roleLabel + " content must not be blank");
        }
    }

    /**
     * 按角色校验字段组合，防止构造出 Provider 无法接受的消息。
     *
     * @param role             消息角色
     * @param content          文本内容
     * @param toolCalls        工具调用列表
     * @param toolCallId       工具调用 ID
     * @param reasoningContent 思考内容
     */
    private static void validate(
            Role role,
            String content,
            List<ToolCall> toolCalls,
            String toolCallId,
            String reasoningContent
    ) {
        switch (role) {
            case SYSTEM, USER -> {
                // 系统和用户消息不允许携带工具调用或厂商推理扩展。
                if (content == null || content.isBlank()) {
                    throw new IllegalArgumentException(role + " content must not be blank");
                }
                if (!toolCalls.isEmpty()) {
                    throw new IllegalArgumentException(role + " must not have toolCalls");
                }
                if (toolCallId != null) {
                    throw new IllegalArgumentException(role + " must not have toolCallId");
                }
                if (reasoningContent != null) {
                    throw new IllegalArgumentException(role + " must not have reasoningContent");
                }
            }
            case ASSISTANT -> {
                // assistant 仅可包含正文、工具调用和可选思考内容。
                if (toolCallId != null) {
                    throw new IllegalArgumentException("ASSISTANT must not have toolCallId");
                }
            }
            case TOOL -> {
                // 工具消息必须精确关联某一次工具调用，且不能附带 assistant 扩展字段。
                if (content == null) {
                    throw new IllegalArgumentException("TOOL content must not be null");
                }
                if (!toolCalls.isEmpty()) {
                    throw new IllegalArgumentException("TOOL must not have toolCalls");
                }
                if (toolCallId == null || toolCallId.isBlank()) {
                    throw new IllegalArgumentException("TOOL toolCallId must not be blank");
                }
                if (reasoningContent != null) {
                    throw new IllegalArgumentException("TOOL must not have reasoningContent");
                }
            }
        }
    }
}
