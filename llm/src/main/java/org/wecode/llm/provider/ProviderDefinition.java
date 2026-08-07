package org.wecode.llm.provider;

/**
 * 已解析的 Provider 协议定义。
 * <p>
 * 该定义与 CLI 的 YAML 映射对象解耦，使 llm 模块只关心连接参数和请求协议。
 *
 * @param name                 Provider 档案名，仅用于诊断信息
 * @param protocol             请求协议标识
 * @param baseUrl              API 根地址
 * @param apiKey               认证密钥
 * @param model                模型标识
 * @param temperature          非思考模式下的可选采样温度
 * @param thinkingEnabled      是否提取并回传思考内容；同时决定专用开关的 enabled/disabled 值
 * @param sendThinkingToggle   是否发送 DeepSeek 风格的思考模式开关扩展
 * @param reasoningEffort      可选思考强度
 * @param thinkingResponseField assistant 消息中思考内容的字段名
 */
public record ProviderDefinition(
        String name,
        String protocol,
        String baseUrl,
        String apiKey,
        String model,
        Double temperature,
        Boolean thinkingEnabled,
        Boolean sendThinkingToggle,
        String reasoningEffort,
        String thinkingResponseField
) {
}
