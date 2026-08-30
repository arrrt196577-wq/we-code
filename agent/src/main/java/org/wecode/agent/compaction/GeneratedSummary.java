package org.wecode.agent.compaction;

import java.util.Objects;

/**
 * 摘要模型成功返回的可提交文本。
 * <p>
 * 本对象尚未写入会话数据库；后续 `/compact` 任务负责将其与稳定快照一起提交为检查点。
 *
 * @param content 模型生成的结构化摘要正文
 */
public record GeneratedSummary(String content) {

    /** 校验摘要正文可替代一段历史上下文。 */
    public GeneratedSummary {
        content = Objects.requireNonNull(content, "content").strip();
        // 空白文本无法承载任何历史事实，禁止交给后续持久化流程。
        if (content.isEmpty()) {
            throw new IllegalArgumentException("summary content must not be blank");
        }
    }
}
