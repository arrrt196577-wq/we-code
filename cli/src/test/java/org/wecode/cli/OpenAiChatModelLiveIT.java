package org.wecode.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.wecode.cli.config.ConfigResolver;
import org.wecode.cli.config.LlmCliOverrides;
import org.wecode.cli.config.LlmConfig;
import org.wecode.cli.config.WeCodeConfig;
import org.wecode.cli.config.YamlConfigLoader;
import org.wecode.llm.chat.OpenAiChatModel;
import org.wecode.llm.chat.OpenAiChatModel.ThinkingOptions;
import org.wecode.llm.model.FinishReason;
import org.wecode.llm.model.LlmResponse;
import org.wecode.llm.model.Message;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 真实发网 smoke：仅在仓库根（或上级）存在 {@code wecode.yml} 且配置了 api-key 时执行。
 * <p>
 * 跑法：在仓库根执行 {@code mvn -pl cli test -Dtest=OpenAiChatModelLiveIT}
 */
class OpenAiChatModelLiveIT {

    @Test
    @EnabledIf("hasLocalConfigWithApiKey")
    void chatReturnsAssistantContent() {
        LlmConfig llm = loadResolvedConfig();
        OpenAiChatModel chatModel = new OpenAiChatModel(
                llm.baseUrl(),
                llm.apiKey(),
                llm.model(),
                llm.temperature(),
                new ThinkingOptions(
                        llm.reasoningEffort(),
                        llm.returnThinking(),
                        llm.thinkingFieldName()
                )
        );

        LlmResponse response = chatModel.chat(
                List.of(Message.user("请只回复：ok")),
                List.of()
        );

        assertEquals(FinishReason.STOP, response.finishReason());
        assertNotNull(response.content());
        assertFalse(response.content().isBlank());
        System.out.println("live content  : " + response.content());
        // 配置了 return-thinking 时顺带打印，不强制断言（网关可能截断）
        if (response.hasThinking()) {
            System.out.println("live thinking : " + response.thinking());
        }
    }

    /** JUnit EnabledIf：本地有可发网的配置才跑。 */
    static boolean hasLocalConfigWithApiKey() {
        Path path = resolveConfigPath();
        // 没有配置文件则跳过
        if (path == null) {
            return false;
        }
        LlmConfig llm = ConfigResolver.resolveLlm(
                YamlConfigLoader.load(path).llm(),
                LlmCliOverrides.none()
        );
        return llm.apiKey() != null && !llm.apiKey().isBlank();
    }

    private static LlmConfig loadResolvedConfig() {
        Path path = resolveConfigPath();
        WeCodeConfig fileConfig = YamlConfigLoader.load(path);
        return ConfigResolver.resolveLlm(fileConfig.llm(), LlmCliOverrides.none());
    }

    /**
     * 解析 wecode.yml：优先当前目录，再试上一级（从 cli 模块跑 test 时）。
     *
     * @return 存在的路径；都没有则 null
     */
    private static Path resolveConfigPath() {
        Path local = Path.of("wecode.yml");
        // 仓库根直接跑
        if (Files.isRegularFile(local)) {
            return local;
        }
        Path parent = Path.of("..", "wecode.yml");
        // 从 cli 模块目录跑
        if (Files.isRegularFile(parent)) {
            return parent;
        }
        return null;
    }
}
