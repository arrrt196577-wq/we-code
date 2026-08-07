package org.wecode.cli.config;

import org.wecode.llm.provider.ChatModelFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * 解析并校验 WeCode 配置。
 * <p>
 * 配置文件声明 Provider 档案及密钥来源；本类在启动时选择档案并解析对应密钥。
 */
public final class ConfigResolver {

    private ConfigResolver() {
    }

    /**
     * 选择当前激活的 Provider，并从其指定的环境变量读取 API Key。
     *
     * @param config yml 中的 LLM 配置；可为 {@code null}
     * @return 已解析且可用于创建 ChatModel 的运行时配置
     */
    public static ResolvedProviderConfig resolveActiveProvider(LlmConfig config) {
        return resolveActiveProvider(config, System::getenv);
    }

    /**
     * 选择当前激活的 Provider；仅供同包测试注入确定的环境变量读取方式。
     *
     * @param config      yml 中的 LLM 配置；可为 {@code null}
     * @param environment 根据变量名读取环境变量的函数
     * @return 已解析且可用于创建 ChatModel 的运行时配置
     */
    static ResolvedProviderConfig resolveActiveProvider(
            LlmConfig config,
            Function<String, String> environment
    ) {
        Objects.requireNonNull(environment, "environment");
        LlmConfig llm = config == null ? LlmConfig.defaults() : config;
        String activeProvider = requireNonBlank(
                llm.activeProvider(),
                "缺少 llm.active-provider；请在 wecode.yml 选择一个 provider"
        );

        Map<String, ProviderConfig> providers = llm.providers();
        ProviderConfig provider = providers.get(activeProvider);
        // 指定的档案不存在时列出可选项，避免用户误以为是网络或密钥问题。
        if (provider == null) {
            throw new IllegalStateException(
                    "未找到 llm.active-provider=" + activeProvider
                            + "；可选值: " + availableProviderNames(providers)
            );
        }

        String protocol = requireNonBlank(
                provider.protocol(),
                "Provider " + activeProvider + " 缺少 protocol"
        );
        // 协议未注册时尽早失败，避免被后续的密钥校验掩盖真实配置问题。
        if (!ChatModelFactory.supports(protocol)) {
            throw new IllegalStateException(
                    "Provider " + activeProvider + " 使用了不支持的 protocol: " + protocol
            );
        }
        String baseUrl = requireNonBlank(
                provider.baseUrl(),
                "Provider " + activeProvider + " 缺少 base-url"
        );
        validateBaseUrl(activeProvider, baseUrl);
        String model = requireNonBlank(
                provider.model(),
                "Provider " + activeProvider + " 缺少 model"
        );
        ThinkingConfig thinking = provider.resolvedThinking();
        validateThinking(activeProvider, thinking);

        String apiKey = firstNonBlank(
                provider.apiKey(),
                readApiKeyFromEnvironment(provider.apiKeyEnv(), environment)
        );
        // 只报告配置字段或环境变量名，绝不在异常或日志中输出密钥内容。
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "Provider " + activeProvider
                            + " 缺少 API Key；请配置 api-key 或设置 api-key-env 指定的环境变量"
            );
        }

        return new ResolvedProviderConfig(
                activeProvider,
                protocol,
                baseUrl,
                apiKey,
                model,
                provider.temperature(),
                thinking
        );
    }

    /**
     * 解析 WeCode 外部数据根目录。
     *
     * @param storage YAML 中的存储配置；可为 {@code null}
     * @return 已规范化的绝对外部数据目录
     */
    public static Path resolveStorageRoot(StorageConfig storage) {
        return StoragePathResolver.resolveRoot(storage);
    }

    /**
     * 校验 Provider URL 是一个可用的 HTTP(S) API 根地址。
     *
     * @param providerName Provider 档案名
     * @param baseUrl      待校验的 API 根地址
     */
    private static void validateBaseUrl(String providerName, String baseUrl) {
        try {
            URI uri = new URI(baseUrl);
            // 非 HTTP 协议无法被 JDK HttpClient 用于远程 API 调用。
            if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalStateException(
                        "Provider " + providerName + " 的 base-url 必须使用 http 或 https: " + baseUrl
                );
            }
            // 缺少 host 的 URI 不能构成远程服务地址。
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalStateException(
                        "Provider " + providerName + " 的 base-url 缺少主机名: " + baseUrl
                );
            }
        } catch (URISyntaxException e) {
            throw new IllegalStateException(
                    "Provider " + providerName + " 的 base-url 不合法: " + baseUrl,
                    e
            );
        }
    }

    /**
     * 校验思考模式的参数组合。
     *
     * @param providerName Provider 档案名
     * @param thinking     思考模式配置
     */
    private static void validateThinking(String providerName, ThinkingConfig thinking) {
        // 仅在已声明思考模式状态时才允许发送厂商专用的切换字段。
        if (Boolean.TRUE.equals(thinking.sendToggle()) && thinking.enabled() == null) {
            throw new IllegalStateException(
                    "Provider " + providerName
                            + " 配置了 thinking.send-toggle，但缺少 thinking.enabled"
            );
        }
        // 未设置思考强度时无需额外约束。
        if (thinking.reasoningEffort() == null || thinking.reasoningEffort().isBlank()) {
            return;
        }
        // 只有显式开启思考模式时，思考强度才具有明确语义。
        if (!Boolean.TRUE.equals(thinking.enabled())) {
            throw new IllegalStateException(
                    "Provider " + providerName
                            + " 配置了 thinking.reasoning-effort，但 thinking.enabled 不是 true"
            );
        }
    }

    /**
     * 收集可选 Provider 名称并按字母排序，确保错误信息稳定。
     *
     * @param providers Provider 配置映射
     * @return 可读的 Provider 名称列表
     */
    private static List<String> availableProviderNames(Map<String, ProviderConfig> providers) {
        List<String> names = new ArrayList<>(providers.keySet());
        names.sort(String::compareTo);
        return names;
    }

    /**
     * 按 Provider 配置读取 API Key 环境变量。
     *
     * @param apiKeyEnv  环境变量名；可为 {@code null}
     * @param environment 环境变量读取函数
     * @return 读取到的值；未配置变量名时返回 {@code null}
     */
    private static String readApiKeyFromEnvironment(
            String apiKeyEnv,
            Function<String, String> environment
    ) {
        // 未配置环境变量时允许仅使用 yml 中的 api-key。
        if (apiKeyEnv == null || apiKeyEnv.isBlank()) {
            return null;
        }
        return environment.apply(apiKeyEnv);
    }

    /**
     * 返回第一个非空白文本。
     *
     * @param values 候选文本，按优先级排序
     * @return 第一个非空白文本；全部为空时返回 {@code null}
     */
    private static String firstNonBlank(String... values) {
        // 未传候选值时没有可用结果。
        if (values == null) {
            return null;
        }
        for (String value : values) {
            // 空白值不具备密钥语义，继续检查下一个来源。
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /**
     * 要求文本配置非空。
     *
     * @param value        待校验文本
     * @param errorMessage 校验失败时的错误信息
     * @return 已校验的文本
     */
    private static String requireNonBlank(String value, String errorMessage) {
        // null 和空白字符串都视为缺失配置。
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(errorMessage);
        }
        return value;
    }
}
