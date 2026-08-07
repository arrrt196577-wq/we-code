package org.wecode.llm.provider;

import org.wecode.llm.chat.ChatModel;
import org.wecode.llm.chat.OpenAiChatModel;

import java.util.Objects;

/**
 * 根据 Provider 协议定义创建 ChatModel。
 * <p>
 * Provider 档案名不参与分支：同一协议的任意服务均复用相同适配器。
 */
public final class ChatModelFactory {

    /** OpenAI Chat Completions 兼容协议标识。 */
    public static final String OPENAI_CHAT_COMPLETIONS = "openai-chat-completions";

    private ChatModelFactory() {
    }

    /**
     * 创建与指定协议对应的 ChatModel。
     *
     * @param definition 已校验的 Provider 协议定义
     * @return 可供 AgentLoop 调用的 ChatModel
     */
    public static ChatModel create(ProviderDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        // 仅由 protocol 决定实现，新增同协议 Provider 时无需改动源码。
        return switch (definition.protocol()) {
            case OPENAI_CHAT_COMPLETIONS -> new OpenAiChatModel(
                    definition.baseUrl(),
                    definition.apiKey(),
                    definition.model(),
                    definition.temperature(),
                    new OpenAiChatModel.ThinkingOptions(
                            definition.thinkingEnabled(),
                            definition.sendThinkingToggle(),
                            definition.reasoningEffort(),
                            definition.thinkingResponseField()
                    )
            );
            default -> throw new IllegalArgumentException(
                    "不支持的 LLM protocol: " + definition.protocol()
                            + "（Provider: " + definition.name() + "）"
            );
        };
    }

    /**
     * 判断当前版本是否支持指定的请求协议。
     *
     * @param protocol 待检查的协议标识
     * @return 支持时返回 {@code true}
     */
    public static boolean supports(String protocol) {
        // 当前仅注册 OpenAI Chat Completions 兼容适配器。
        return OPENAI_CHAT_COMPLETIONS.equals(protocol);
    }
}
