package org.wecode.session.persistence.payload;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import org.wecode.llm.model.FinishReason;

import java.util.Objects;

/**
 * assistant 停止原因的稳定持久化枚举。
 *
 * <p>显式维护 JSON 值，避免以后重命名 Java 枚举常量时破坏历史数据。</p>
 */
public enum AssistantFinishReason {

    STOP("stop"),
    TOOL_CALLS("tool_calls"),
    LENGTH("length"),
    ERROR("error");

    private final String jsonValue;

    AssistantFinishReason(String jsonValue) {
        this.jsonValue = jsonValue;
    }

    /**
     * 返回稳定的数据库 JSON 表示。
     *
     * @return 小写蛇形命名的停止原因
     */
    @JsonValue
    public String jsonValue() {
        // JSON 契约与 Java 常量名称解耦。
        return jsonValue;
    }

    /**
     * 从数据库 JSON 读取停止原因。
     *
     * @param value 持久化字符串
     * @return 对应停止原因
     */
    @JsonCreator
    public static AssistantFinishReason fromJsonValue(String value) {
        Objects.requireNonNull(value, "value");
        // 显式枚举合法值，拒绝无法安全恢复的未知停止原因。
        for (AssistantFinishReason reason : values()) {
            // 找到稳定 JSON 值对应的枚举后立即返回。
            if (reason.jsonValue.equals(value)) {
                return reason;
            }
        }
        // 未知值必须由更高版本迁移或 Codec 处理，不能静默降级。
        throw new IllegalArgumentException("Unknown assistant finish reason: " + value);
    }

    /**
     * 将当前运行时停止原因转换为持久化枚举。
     *
     * @param finishReason LLM 模块返回的停止原因
     * @return 稳定持久化枚举
     */
    public static AssistantFinishReason fromRuntime(FinishReason finishReason) {
        Objects.requireNonNull(finishReason, "finishReason");
        // 所有运行时枚举值必须显式映射，新增值时由编译器提示补齐。
        return switch (finishReason) {
            case STOP -> STOP;
            case TOOL_CALLS -> TOOL_CALLS;
            case LENGTH -> LENGTH;
            case ERROR -> ERROR;
        };
    }

    /**
     * 转换为当前 LLM 模块使用的停止原因。
     *
     * @return 可用于重建运行时 assistant 消息的停止原因
     */
    public FinishReason toRuntime() {
        // 持久化枚举与运行时枚举通过显式映射隔离演进。
        return switch (this) {
            case STOP -> FinishReason.STOP;
            case TOOL_CALLS -> FinishReason.TOOL_CALLS;
            case LENGTH -> FinishReason.LENGTH;
            case ERROR -> FinishReason.ERROR;
        };
    }
}
