package org.wecode.cli.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 单个 LLM Provider 档案的原始配置。
 * <p>
 * 密钥可直接写入 {@link #apiKey()}，也可通过 {@link #apiKeyEnv()} 指向环境变量；前者优先。
 *
 * @param protocol    请求协议，例如 {@code openai-chat-completions}
 * @param baseUrl     服务 API 根地址，不包含 {@code /chat/completions}
 * @param apiKeyEnv   保存 API Key 的环境变量名
 * @param model       调用时传给服务端的模型标识
 * @param temperature 非思考模式下的可选采样温度
 * @param thinking    Provider 的可选思考模式配置
 * @param apiKey      可选的直接 API Key；仅适用于被 Git 忽略的本地配置文件
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProviderConfig(
        String protocol,
        @JsonProperty("base-url") String baseUrl,
        @JsonProperty("api-key-env") String apiKeyEnv,
        String model,
        Double temperature,
        ThinkingConfig thinking,
        @JsonProperty("api-key") String apiKey
) {

    /**
     * 返回可供运行时使用的思考配置。
     *
     * @return 显式配置，或不启用思考扩展的默认值
     */
    public ThinkingConfig resolvedThinking() {
        // 未配置时保持通用 OpenAI Chat Completions 请求，不发送厂商扩展字段。
        if (thinking == null) {
            return ThinkingConfig.defaults();
        }
        return thinking;
    }
}
