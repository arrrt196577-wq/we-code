package org.wecode.cli.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Provider 的思考模式配置。
 *
 * @param enabled         是否提取并回传思考内容；同时决定专用开关的 enabled/disabled 值
 * @param reasoningEffort 思考强度，例如 {@code low}/{@code high}/{@code max}
 * @param responseField   响应和 assistant 历史中的思考字段名
 * @param sendToggle      是否发送 DeepSeek 风格的 {@code thinking.type} 扩展字段
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ThinkingConfig(
        Boolean enabled,
        @JsonProperty("reasoning-effort") String reasoningEffort,
        @JsonProperty("response-field") String responseField,
        @JsonProperty("send-toggle") Boolean sendToggle
) {

    /** DeepSeek 等 OpenAI 兼容服务常用的思考字段名。 */
    public static final String DEFAULT_RESPONSE_FIELD = "reasoning_content";

    /**
     * 创建不使用任何思考模式扩展的默认配置。
     *
     * @return 默认思考配置
     */
    public static ThinkingConfig defaults() {
        return new ThinkingConfig(null, null, DEFAULT_RESPONSE_FIELD, null);
    }

    /**
     * 解析思考字段名。
     *
     * @return 非空的字段名
     */
    public String resolvedResponseField() {
        // 用户未指定字段名时使用兼容服务普遍采用的默认值。
        if (responseField == null || responseField.isBlank()) {
            return DEFAULT_RESPONSE_FIELD;
        }
        return responseField;
    }
}
