package org.wecode.cli.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * LLM 相关配置，对应 yml 中的 {@code llm:} 段。
 * <p>
 * 含连接参数与推理/思考相关可选开关；后者仅在 Provider 支持时生效。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LlmConfig(
        @JsonProperty("base-url") String baseUrl,
        @JsonProperty("api-key") String apiKey,
        String model,
        Double temperature,
        /** 推理强度，例如 {@code low}/{@code medium}/{@code high}；不支持时可为空 */
        @JsonProperty("reasoning-effort") String reasoningEffort,
        /** 是否在响应中返回思考内容 */
        @JsonProperty("return-thinking") Boolean returnThinking,
        /** 是否在请求中发送思考内容 */
        @JsonProperty("send-thinking") Boolean sendThinking,
        /** 自定义思考字段名；为空则用 Provider 默认字段 */
        @JsonProperty("thinking-field-name") String thinkingFieldName
) {
    /**
     * 缺省配置：文件不存在或字段省略时使用。
     */
    public static LlmConfig defaults() {
        return new LlmConfig(
                "https://api.openai.com/v1",
                null,
                "gpt-4o-mini",
                0.2,
                null,
                null,
                null,
                null
        );
    }
}
