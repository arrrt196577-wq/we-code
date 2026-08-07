package org.wecode.cli.config;

import org.wecode.llm.provider.ProviderDefinition;

/**
 * 已完成选择、校验和密钥解析的 Provider 运行时配置。
 * <p>
 * 此对象包含 API Key，只能在进程内短暂使用，禁止写入日志或持久化。
 *
 * @param name        Provider 档案名
 * @param protocol    请求协议
 * @param baseUrl     API 根地址
 * @param apiKey      已从环境变量读取的 API Key
 * @param model       模型标识
 * @param temperature 非思考模式的可选温度
 * @param thinking    思考模式配置
 */
public record ResolvedProviderConfig(
        String name,
        String protocol,
        String baseUrl,
        String apiKey,
        String model,
        Double temperature,
        ThinkingConfig thinking
) {

    /**
     * 转换为 llm 模块可消费的协议定义，避免 llm 模块依赖 CLI 配置实现。
     *
     * @return 协议工厂的输入定义
     */
    public ProviderDefinition toProviderDefinition() {
        ThinkingConfig options = thinking == null ? ThinkingConfig.defaults() : thinking;
        return new ProviderDefinition(
                name,
                protocol,
                baseUrl,
                apiKey,
                model,
                temperature,
                options.enabled(),
                options.sendToggle(),
                options.reasoningEffort(),
                options.resolvedResponseField()
        );
    }
}
