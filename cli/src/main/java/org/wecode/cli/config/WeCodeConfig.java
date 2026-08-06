package org.wecode.cli.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 根配置对象，对应 {@code wecode.yml} 整文件。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WeCodeConfig(LlmConfig llm, StorageConfig storage) {

    /**
     * 空配置：使用 LLM 默认值。
     */
    public static WeCodeConfig defaults() {
        return new WeCodeConfig(LlmConfig.defaults(), StorageConfig.defaults());
    }
}
