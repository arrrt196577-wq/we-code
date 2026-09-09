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
    /** 打包在 CLI Jar 中的默认配置资源名称。 */
    private static final String BUNDLED_CONFIG_RESOURCE = "wecode.yml";

    private YamlConfigLoader() {
    }

    /**
     * 加载配置文件；文件不存在时返回默认配置，而不是抛错。
     *
     * @param path yml 路径，例如 {@code wecode.yml}
     * @return 解析后的配置；外部文件缺失时回退到 Jar 内置配置，Jar 中也不存在时返回空配置
     */
    public static WeCodeConfig load(Path path) {
        // 优先使用启动目录或 --config 指定的外部配置，便于单次启动覆盖内置默认值。
        if (path != null && Files.isRegularFile(path)) {
            return loadFromFile(path);
        }

        // 外部配置不存在时读取随 CLI Jar 一同发布的默认配置，保证任意工作目录都能启动。
        return loadBundledConfig();
    }

    /**
     * 从外部 YAML 文件读取配置。
     *
     * @param path 外部配置文件路径
     * @return 完成空节点规范化后的配置
     */
    private static WeCodeConfig loadFromFile(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            return parse(in);
        } catch (IOException e) {
            throw new IllegalStateException("无法读取配置文件: " + path.toAbsolutePath(), e);
        }
    }

    /**
     * 从 CLI Jar 读取随应用发布的默认 YAML 配置。
     *
     * @return 完成空节点规范化后的配置；资源缺失时返回空配置
     */
    private static WeCodeConfig loadBundledConfig() {
        InputStream resource = YamlConfigLoader.class.getClassLoader()
                .getResourceAsStream(BUNDLED_CONFIG_RESOURCE);
        // 未打包配置时保持兼容原有空配置行为，随后由配置校验器给出明确错误。
        if (resource == null) {
            return WeCodeConfig.defaults();
        }

        try (InputStream in = resource) {
            return parse(in);
        } catch (IOException e) {
            throw new IllegalStateException("无法读取 Jar 内置配置: " + BUNDLED_CONFIG_RESOURCE, e);
        }
    }

    /**
     * 将 YAML 流转换为完整的根配置，补齐可选节点的空默认值。
     *
     * @param input YAML 配置内容流
     * @return LLM 和存储节点均已完成规范化的配置
     * @throws IOException YAML 内容无法读取或反序列化时抛出
     */
    private static WeCodeConfig parse(InputStream input) throws IOException {
        WeCodeConfig config = YAML_MAPPER.readValue(input, WeCodeConfig.class);
        // 空文件或只有注释时，Jackson 可能返回 null。
        if (config == null) {
            return WeCodeConfig.defaults();
        }
        // LLM 段可省略；保留 storage，避免只有存储配置时被默认值覆盖。
        return new WeCodeConfig(
                config.llm() != null ? config.llm() : LlmConfig.defaults(),
                config.storage() != null ? config.storage() : StorageConfig.defaults()
        );
    }
}
