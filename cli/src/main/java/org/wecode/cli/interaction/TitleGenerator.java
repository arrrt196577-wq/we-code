package org.wecode.cli.interaction;

import java.util.Optional;

/**
 * 根据 session 首条用户消息生成展示标题。
 */
@FunctionalInterface
public interface TitleGenerator {

    /**
     * 尝试生成一个标题。
     *
     * @param firstUserMessage session 的首条用户消息
     * @return 合法标题；模型无法提供时返回空
     */
    Optional<String> generate(String firstUserMessage);
}
