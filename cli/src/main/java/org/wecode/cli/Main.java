package org.wecode.cli;

import org.wecode.cli.config.ConfigResolver;
import org.wecode.cli.config.LlmCliOverrides;
import org.wecode.cli.config.LlmConfig;
import org.wecode.cli.config.WeCodeConfig;
import org.wecode.cli.config.YamlConfigLoader;

import java.nio.file.Path;

/**
 * CLI 入口：解析参数、加载 yml、组装依赖并启动会话。
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        // 默认读当前目录下的 wecode.yml；可用 --config 覆盖（后续做完整参数解析时接入）
        Path configPath = Path.of("wecode.yml");
        WeCodeConfig fileConfig = YamlConfigLoader.load(configPath);

        // 合并：环境变量 / 未来的 CLI 参数 覆盖 yml
        LlmConfig llm = ConfigResolver.resolveLlm(fileConfig.llm(), LlmCliOverrides.none());

        System.out.println("config file : " + configPath.toAbsolutePath());
        System.out.println("base-url    : " + llm.baseUrl());
        System.out.println("model       : " + llm.model());
        System.out.println("api-key set : " + (llm.apiKey() != null && !llm.apiKey().isBlank()));

        // TODO: requireApiKey → 装配 OpenAiChatModel / AgentLoop 并运行任务
    }
}
