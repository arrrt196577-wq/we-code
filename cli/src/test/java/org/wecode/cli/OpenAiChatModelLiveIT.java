package org.wecode.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.wecode.cli.config.ConfigResolver;
import org.wecode.cli.config.LlmConfig;
import org.wecode.cli.config.ResolvedProviderConfig;
import org.wecode.cli.config.WeCodeConfig;
import org.wecode.cli.config.YamlConfigLoader;
import org.wecode.llm.chat.ChatModel;
import org.wecode.llm.model.FinishReason;
import org.wecode.llm.model.LlmResponse;
import org.wecode.llm.model.LlmUsage;
import org.wecode.llm.model.Message;
import org.wecode.llm.provider.ChatModelFactory;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实发网 smoke 测试：仅在仓库根目录存在新格式的 {@code wecode.yml}，且已设置所选
 * Provider 对应密钥环境变量时执行。
 * <p>
 * 运行命令：{@code mvn -pl cli test -Dtest=OpenAiChatModelLiveIT}
 */
class OpenAiChatModelLiveIT {

    @Test
    @EnabledIf("hasLocalConfigWithApiKey")
    void chatReturnsAssistantContent() {
        ResolvedProviderConfig provider = loadResolvedConfig();
        ChatModel chatModel = ChatModelFactory.create(provider.toProviderDefinition());

        LlmResponse response = chatModel.chat(
                List.of(Message.user("请只回复：ok")),
                List.of()
        );

        assertEquals(FinishReason.STOP, response.finishReason());
        assertNotNull(response.content());
        assertFalse(response.content().isBlank());
        System.out.println("live provider : " + provider.name());
        System.out.println("live content  : " + response.content());
        // 配置了思考模式且服务端返回思考内容时才打印，避免将空值当作异常。
        if (response.hasThinking()) {
            System.out.println("live thinking : " + response.thinking());
        }
    }

    /**
     * 真实调用 DeepSeek，验证响应中的 usage 六个字段均可被适配器完整解析。
     * <p>
     * 该测试只接受官方 {@code api.deepseek.com} 地址，避免把真实接口测试误发到同名的内部网关。
     */
    @Test
    @EnabledIf("hasOfficialDeepSeekConfigWithApiKey")
    void deepSeekChatReturnsCompleteUsage() {
        ResolvedProviderConfig provider = loadDeepSeekConfig();
        ChatModel chatModel = ChatModelFactory.create(provider.toProviderDefinition());

        // 使用最小请求控制真实接口测试成本，并避免工具调用影响 usage 统计。
        LlmResponse response = chatModel.chat(
                List.of(Message.user("请只回复：ok")),
                List.of()
        );

        // DeepSeek 未返回任一字段时必须失败，不能把不完整统计误判为适配成功。
        assertNotNull(response.usage(), "DeepSeek 响应缺少完整 usage");
        LlmUsage usage = response.usage();
        assertTrue(usage.inputTokens() > 0, "prompt_tokens 应大于 0");
        assertTrue(usage.outputTokens() > 0, "completion_tokens 应大于 0");
        assertEquals(
                usage.inputTokens() + usage.outputTokens(),
                usage.totalTokens(),
                "total_tokens 应等于输入与输出 token 之和"
        );
        assertNotNull(usage.cachedInputTokens(), "缺少 prompt_cache_hit_tokens");
        assertNotNull(usage.uncachedInputTokens(), "缺少 prompt_cache_miss_tokens");
        assertNotNull(usage.reasoningTokens(), "缺少 completion_tokens_details.reasoning_tokens");
        assertTrue(usage.cachedInputTokens() >= 0, "prompt_cache_hit_tokens 不能为负数");
        assertTrue(usage.uncachedInputTokens() >= 0, "prompt_cache_miss_tokens 不能为负数");
        assertTrue(usage.reasoningTokens() >= 0, "reasoning_tokens 不能为负数");

        // 输出非敏感统计值，便于人工核验实际 Provider 的字段是否变化。
        System.out.println("DeepSeek usage: " + usage);
    }

    /**
     * 判断本机是否具备运行真实网络测试的最小条件。
     *
     * @return 配置文件存在且所选 Provider 的 API Key 可读取时返回 {@code true}
     */
    static boolean hasLocalConfigWithApiKey() {
        Path path = resolveConfigPath();
        // 未提供本地配置时跳过真实发网测试。
        if (path == null) {
            return false;
        }
        try {
            ConfigResolver.resolveActiveProvider(YamlConfigLoader.load(path).llm());
            return true;
        } catch (IllegalStateException e) {
            // 配置仍为旧格式、密钥不存在或内容不完整时不执行集成测试。
            return false;
        }
    }

    /**
     * 判断本地是否配置了可安全执行的 DeepSeek 官方接口真实测试。
     *
     * @return 存在 deepseek 档案、密钥可用且地址为官方接口时返回 {@code true}
     */
    static boolean hasOfficialDeepSeekConfigWithApiKey() {
        Path path = resolveConfigPath();
        // 未提供本地配置时跳过真实发网测试。
        if (path == null) {
            return false;
        }
        try {
            ResolvedProviderConfig provider = loadDeepSeekConfig();
            return "api.deepseek.com".equalsIgnoreCase(URI.create(provider.baseUrl()).getHost());
        } catch (IllegalArgumentException | IllegalStateException exception) {
            // 缺失档案、密钥或 URL 非法时不执行，避免默认构建访问网络。
            return false;
        }
    }

    /**
     * 加载并解析当前激活的 Provider。
     *
     * @return 可直接构建 ChatModel 的运行时 Provider 配置
     */
    private static ResolvedProviderConfig loadResolvedConfig() {
        Path path = resolveConfigPath();
        WeCodeConfig fileConfig = YamlConfigLoader.load(path);
        return ConfigResolver.resolveActiveProvider(fileConfig.llm());
    }

    /**
     * 加载名为 deepseek 的档案，而不是依赖当前 active-provider 的选择。
     *
     * @return 已校验的 DeepSeek 运行时配置
     */
    private static ResolvedProviderConfig loadDeepSeekConfig() {
        Path path = resolveConfigPath();
        WeCodeConfig fileConfig = YamlConfigLoader.load(path);
        LlmConfig deepSeekOnlyConfig = new LlmConfig("deepseek", fileConfig.llm().providers());
        return ConfigResolver.resolveActiveProvider(deepSeekOnlyConfig);
    }

    /**
     * 解析本地配置文件：优先当前目录，再尝试上一级目录。
     *
     * @return 存在的配置路径；未找到时返回 {@code null}
     */
    private static Path resolveConfigPath() {
        Path local = Path.of("wecode.yml");
        // 在仓库根目录运行测试时直接使用当前目录的配置。
        if (Files.isRegularFile(local)) {
            return local;
        }
        Path parent = Path.of("..", "wecode.yml");
        // 在 cli 模块目录运行测试时使用上一级仓库根目录配置。
        if (Files.isRegularFile(parent)) {
            return parent;
        }
        return null;
    }
}
