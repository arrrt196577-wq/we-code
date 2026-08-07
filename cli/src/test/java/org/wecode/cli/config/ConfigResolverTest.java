package org.wecode.cli.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ConfigResolver} 的 Provider 选择和启动期校验测试。
 */
class ConfigResolverTest {

    @Test
    void resolvesTheActiveProviderAndItsDedicatedApiKeyEnvironmentVariable() {
        LlmConfig config = new LlmConfig(
                "deepseek",
                Map.of(
                        "deepseek", new ProviderConfig(
                                "openai-chat-completions",
                                "https://api.deepseek.com",
                                "DEEPSEEK_API_KEY",
                                "deepseek-v4-flash",
                                null,
                                new ThinkingConfig(true, "high", "reasoning_content", true),
                                null
                        ),
                        "openai", new ProviderConfig(
                                "openai-chat-completions",
                                "https://api.openai.com/v1",
                                "OPENAI_API_KEY",
                                "gpt-5.4",
                                0.2,
                                null,
                                null
                        )
                )
        );

        ResolvedProviderConfig provider = ConfigResolver.resolveActiveProvider(
                config,
                key -> "DEEPSEEK_API_KEY".equals(key) ? "sk-deepseek-test" : null
        );

        assertEquals("deepseek", provider.name());
        assertEquals("https://api.deepseek.com", provider.baseUrl());
        assertEquals("deepseek-v4-flash", provider.model());
        assertTrue(provider.thinking().enabled());
    }

    @Test
    void rejectsAnUnknownActiveProviderWithAvailableNames() {
        LlmConfig config = new LlmConfig(
                "missing",
                Map.of(
                        "openai", new ProviderConfig(
                                "openai-chat-completions",
                                "https://api.openai.com/v1",
                                "OPENAI_API_KEY",
                                "gpt-5.4",
                                null,
                                null,
                                null
                        )
                )
        );

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> ConfigResolver.resolveActiveProvider(config, key -> "sk-test")
        );

        assertTrue(error.getMessage().contains("missing"));
        assertTrue(error.getMessage().contains("openai"));
    }

    @Test
    void rejectsReasoningEffortWhenThinkingIsNotEnabled() {
        LlmConfig config = new LlmConfig(
                "deepseek",
                Map.of("deepseek", new ProviderConfig(
                        "openai-chat-completions",
                        "https://api.deepseek.com",
                        "DEEPSEEK_API_KEY",
                        "deepseek-v4-flash",
                        null,
                        new ThinkingConfig(false, "high", "reasoning_content", false),
                        null
                ))
        );

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> ConfigResolver.resolveActiveProvider(config, key -> "sk-test")
        );

        assertTrue(error.getMessage().contains("reasoning-effort"));
    }

    @Test
    void rejectsAnUnsupportedProtocolBeforeReadingTheApiKey() {
        LlmConfig config = new LlmConfig(
                "custom",
                Map.of("custom", new ProviderConfig(
                        "anthropic-messages",
                        "https://api.example.com",
                        "UNSET_API_KEY",
                        "example-model",
                        null,
                        null,
                        null
                ))
        );

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> ConfigResolver.resolveActiveProvider(config, key -> null)
        );

        assertTrue(error.getMessage().contains("anthropic-messages"));
    }

    @Test
    void prefersApiKeyConfiguredInYamlOverTheEnvironmentVariable() {
        LlmConfig config = new LlmConfig(
                "deepseek",
                Map.of("deepseek", new ProviderConfig(
                        "openai-chat-completions",
                        "https://api.deepseek.com",
                        "DEEPSEEK_API_KEY",
                        "deepseek-v4-flash",
                        null,
                        null,
                        "sk-config-test"
                ))
        );

        ResolvedProviderConfig provider = ConfigResolver.resolveActiveProvider(
                config,
                key -> "sk-environment-test"
        );

        assertEquals("sk-config-test", provider.apiKey());
    }
}
