package org.wecode.cli.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * LLM 配置根节点，对应 yml 中的 {@code llm:} 段。
 * <p>
 * Provider 名称仅用于选择配置档案；真正决定请求格式的是各档案中的 {@code protocol}。
 *
 * @param activeProvider 当前激活的 Provider 档案名
 * @param providers      按档案名索引的 Provider 配置
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LlmConfig(
        @JsonProperty("active-provider") String activeProvider,
        Map<String, ProviderConfig> providers
) {

    public LlmConfig {
        // 未声明 providers 时按空配置处理，后续由解析器给出明确错误。
        providers = providers == null ? Map.of() : Map.copyOf(providers);
    }

    /**
     * 空配置默认值；不隐式选择任意云厂商，避免误发起远程调用。
     *
     * @return 不含激活 Provider 的空配置
     */
    public static LlmConfig defaults() {
        return new LlmConfig(null, Map.of());
    }
}
