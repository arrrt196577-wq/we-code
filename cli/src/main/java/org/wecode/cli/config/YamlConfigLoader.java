package org.wecode.cli.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 从 YAML 文件加载 {@link WeCodeConfig}。
 * <p>
 * 不依赖 Spring：用 Jackson YAML 把文件反序列化成 Java record。
 */
public final class YamlConfigLoader {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    private YamlConfigLoader() {
    }

    /**
     * 加载配置文件；文件不存在时返回默认配置，而不是抛错。
     *
     * @param path yml 路径，例如 {@code wecode.yml}
     * @return 解析后的配置；文件缺失时为 {@link WeCodeConfig#defaults()}
     */
    public static WeCodeConfig load(Path path) {
        // 文件不存在：走代码内默认值，方便首次克隆仓库就能启动
        if (path == null || !Files.isRegularFile(path)) {
            return WeCodeConfig.defaults();
        }

        try (InputStream in = Files.newInputStream(path)) {
            WeCodeConfig config = YAML_MAPPER.readValue(in, WeCodeConfig.class);
            // 空文件或只有注释时，Jackson 可能返回 null
            if (config == null) {
                return WeCodeConfig.defaults();
            }
            // LLM 段可省略；保留 storage，避免只有存储配置时被默认值覆盖。
            return new WeCodeConfig(
                    config.llm() != null ? config.llm() : LlmConfig.defaults(),
                    config.storage() != null ? config.storage() : StorageConfig.defaults()
            );
        } catch (IOException e) {
            throw new IllegalStateException("无法读取配置文件: " + path.toAbsolutePath(), e);
        }
    }
}
