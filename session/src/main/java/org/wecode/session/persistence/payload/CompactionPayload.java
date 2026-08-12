package org.wecode.session.persistence.payload;

import org.wecode.llm.model.Message;
import org.wecode.session.persistence.entity.SessionMessageType;

import java.util.Objects;

/**
 * 上下文压缩检查点的可回灌文本。
 *
 * @param renderedMemory  已渲染完成、可直接作为 system 上下文使用的记忆文本
 * @param strategyVersion 生成记忆文本的压缩策略版本
 */
public record CompactionPayload(String renderedMemory, String strategyVersion) implements SessionMessagePayload {

    /** 校验压缩结果和策略版本均可用于恢复。 */
    public CompactionPayload {
        renderedMemory = requireNonBlank(renderedMemory, "renderedMemory");
        strategyVersion = requireNonBlank(strategyVersion, "strategyVersion");
    }

    @Override
    public SessionMessageType messageType() {
        // 压缩载荷只能写入 COMPACTION 类型的历史事件。
        return SessionMessageType.COMPACTION;
    }

    /**
     * 将压缩记忆转换为固定角色的模型上下文。
     *
     * @return 作为 system 上下文回灌的压缩记忆
     */
    public Message toContextMessage() {
        // 压缩记忆固定以 system 角色恢复，避免持久化数据任意改变角色。
        return Message.system(renderedMemory);
    }

    /** 校验必须存在的非空白字段。 */
    private static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        // 空白压缩内容不能替代任何历史上下文。
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        // 保留压缩器产生的原始文本。
        return value;
    }
}
