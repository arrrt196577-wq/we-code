package org.wecode.agent;

import org.wecode.llm.model.Message;
import org.wecode.llm.model.ToolCall;
import org.wecode.llm.model.ToolSpec;

import java.util.List;
import java.util.Objects;

/**
 * 基于 Unicode 字符数的输入 token 估算器。
 * <p>
 * 当前 MVP 采用 {@code ceil(字符数 / 4)}。它不是模型 tokenizer，结果仅用于压缩预检，
 * 因此会额外依赖上下文窗口安全余量避免低估风险。
 */
public final class CharacterTokenEstimator {

    /** 当前 MVP 假设四个 Unicode 字符约等于一个 token。 */
    private static final long CHARACTERS_PER_TOKEN = 4L;

    /**
     * 估算本轮实际会进入模型上下文的输入 token 数。
     *
     * @param messages 当前会话消息，包含工具调用及工具结果
     * @param tools    当前请求声明的工具定义
     * @return 按字符数除以四向上取整后的输入 token 估算值
     */
    public long estimateInputTokens(List<Message> messages, List<ToolSpec> tools) {
        long characters = countRequestCharacters(messages, tools);
        // 向上取整，确保非四的倍数的内容不会被直接舍弃。
        return Math.ceilDiv(characters, CHARACTERS_PER_TOKEN);
    }

    /**
     * 计算计入估算的 Unicode code point 数。
     *
     * @param messages 当前会话消息
     * @param tools    当前请求声明的工具定义
     * @return 请求语义字段及其结构标签对应的字符数
     */
    public long countRequestCharacters(List<Message> messages, List<ToolSpec> tools) {
        Objects.requireNonNull(messages, "messages");
        List<ToolSpec> toolSpecs = tools == null ? List.of() : tools;
        long characters = 0L;

        // 每条消息都计入角色、正文、工具关联和可选 reasoning，避免只统计正文导致低估。
        for (Message message : messages) {
            characters = Math.addExact(characters, countMessage(Objects.requireNonNull(message, "message")));
        }
        // 工具名称、描述和 JSON Schema 同样会作为请求的一部分发送给模型。
        for (ToolSpec tool : toolSpecs) {
            characters = Math.addExact(characters, countTool(Objects.requireNonNull(tool, "tool")));
        }
        return characters;
    }

    /** 计算一条消息在请求中的保守字符数。 */
    private long countMessage(Message message) {
        long characters = countField("role", message.role().name());
        characters = Math.addExact(characters, countField("content", message.content()));
        characters = Math.addExact(characters, countField("toolCallId", message.toolCallId()));
        characters = Math.addExact(characters, countField("reasoningContent", message.reasoningContent()));

        // assistant 工具调用需要将调用标识、名称和参数全部纳入估算。
        for (ToolCall call : message.toolCalls()) {
            characters = Math.addExact(characters, countField("toolCall.id", call.id()));
            characters = Math.addExact(characters, countField("toolCall.name", call.name()));
            characters = Math.addExact(characters, countField("toolCall.arguments", call.argumentsJson()));
        }
        return characters;
    }

    /** 计算一条工具定义在请求中的保守字符数。 */
    private long countTool(ToolSpec tool) {
        long characters = countField("tool.name", tool.name());
        characters = Math.addExact(characters, countField("tool.description", tool.description()));
        return Math.addExact(characters, countField("tool.parameters", tool.parametersJson()));
    }

    /**
     * 计算字段名和字段值的 Unicode code point 数；空字段不计入值部分。
     *
     * @param fieldName 字段结构标签
     * @param value     字段值，可为空
     * @return 本字段参与估算的字符数
     */
    private static long countField(String fieldName, String value) {
        long characters = codePointCount(fieldName);
        // 空值不携带文本，但保留字段标签以保守覆盖请求结构开销。
        if (value != null) {
            characters = Math.addExact(characters, codePointCount(value));
        }
        return characters;
    }

    /** 使用 code point 而不是 UTF-16 code unit 统计文本，避免 Emoji 被重复计算。 */
    private static long codePointCount(String value) {
        return value.codePointCount(0, value.length());
    }
}
