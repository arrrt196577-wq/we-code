package org.wecode.cli.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * WeCode 外部数据目录配置，对应 {@code wecode.yml} 的 {@code storage:} 节。
 * <p>
 * 外部数据目录用于存放数据库、日志和备份等运行期文件，不应位于项目仓库中。
 * {@code root} 为空时，由 {@link StoragePathResolver} 推导当前 Windows 用户的默认目录。
 *
 * @param root 外部数据根目录的绝对路径，可为空
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StorageConfig(String root) {

    /**
     * 返回未指定根目录的默认配置。
     *
     * @return 由运行环境决定根目录的配置
     */
    public static StorageConfig defaults() {
        return new StorageConfig(null);
    }
}
