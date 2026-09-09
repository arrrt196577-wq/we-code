package org.wecode.cli.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link YamlConfigLoader} 对多 Provider 配置格式的反序列化测试。
 */
class YamlConfigLoaderTest {

    @TempDir
    Path tempDirectory;

    @Test
    void loadsProviderProfilesFromYaml() throws IOException {
        Path configPath = tempDirectory.resolve("wecode.yml");
        Files.writeString(configPath, """
                llm:
                  active-provider: deepseek
                  providers:
                    deepseek:
                      protocol: openai-chat-completions
                      base-url: https://api.deepseek.com
                      api-key-env: DEEPSEEK_API_KEY
                      model: deepseek-v4-flash
                      thinking:
                        enabled: true
                        reasoning-effort: high
                        response-field: reasoning_content
                        send-toggle: true
                    openai:
                      protocol: openai-chat-completions
                      base-url: https://api.openai.com/v1
                      api-key-env: OPENAI_API_KEY
                      model: gpt-5.4
                """);

        WeCodeConfig config = YamlConfigLoader.load(configPath);
        ProviderConfig deepseek = config.llm().providers().get("deepseek");

        assertEquals("deepseek", config.llm().activeProvider());
        assertEquals("deepseek-v4-flash", deepseek.model());
        assertEquals("high", deepseek.thinking().reasoningEffort());
        assertTrue(deepseek.thinking().enabled());
        assertEquals("gpt-5.4", config.llm().providers().get("openai").model());
    }

    @Test
    void loadsBundledConfigWhenExternalConfigIsMissing() {
        WeCodeConfig config = YamlConfigLoader.load(tempDirectory.resolve("missing-wecode.yml"));

        assertEquals("deepseek", config.llm().activeProvider());
        assertTrue(config.llm().providers().containsKey("deepseek"));
    }
}
