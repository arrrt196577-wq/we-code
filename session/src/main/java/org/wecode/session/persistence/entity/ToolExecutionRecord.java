package org.wecode.session.persistence.entity;

import java.util.Objects;

/**
 * {@code tool_execution} 表的一次可恢复工具调用。
 *
 * @param id                 工具执行记录标识
 * @param assistantMessageId 发起调用的 assistant 消息标识
 * @param callId             LLM 返回的工具调用标识
 * @param callIndex          同一 assistant 内的工具调用顺序
 * @param toolName           工具名称
 * @param argumentsJson      工具原始参数 JSON
 * @param status             执行状态
 * @param resultJson         成功结果或失败详情 JSON，可为空
 * @param attemptCount       已执行尝试次数
 * @param leaseToken         当前执行租约令牌，可为空
 * @param leaseUntil         当前租约到期时间，可为空
 * @param revision           记录乐观锁版本
 * @param createdAt          创建时间
 * @param startedAt          本次执行开始时间，可为空
 * @param finishedAt         终态写入时间，可为空
 * @param updatedAt          最后更新时间
 */
public record ToolExecutionRecord(
        String id,
        String assistantMessageId,
        String callId,
        int callIndex,
        String toolName,
        String argumentsJson,
        ToolExecutionStatus status,
        String resultJson,
        int attemptCount,
        String leaseToken,
        Long leaseUntil,
        long revision,
        long createdAt,
        Long startedAt,
        Long finishedAt,
        long updatedAt
) {

    /**
     * 校验工具执行记录的通用字段；JSON 语法合法性由 SQLite 约束保证。
     */
    public ToolExecutionRecord {
        id = requireNonBlank(id, "id");
        assistantMessageId = requireNonBlank(assistantMessageId, "assistantMessageId");
        callId = requireNonBlank(callId, "callId");
        toolName = requireNonBlank(toolName, "toolName");
        argumentsJson = requireNonBlank(argumentsJson, "argumentsJson");
        status = Objects.requireNonNull(status, "status");

        // 同轮调用从零开始按模型原始顺序编号，用于稳定重建 Prompt。
        if (callIndex < 0) {
            throw new IllegalArgumentException("callIndex must be >= 0");
        }
        // 重试次数和乐观锁版本均不允许为负数。
        if (attemptCount < 0 || revision < 0) {
            throw new IllegalArgumentException("attemptCount and revision must be >= 0");
        }
        // 时间戳必须保持单调，避免恢复调度误判租约和终态。
        if (createdAt < 0 || updatedAt < createdAt
                || (startedAt != null && startedAt < createdAt)
                || (finishedAt != null && (startedAt == null || finishedAt < startedAt))) {
            throw new IllegalArgumentException("tool execution timestamps are inconsistent");
        }
    }

    /**
     * 校验不可为空白的标识和 JSON 文本。
     *
     * @param value     待校验文本
     * @param fieldName 字段名称，用于异常信息
     * @return 已校验的原始文本
     */
    private static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        // 空白标识和参数无法被可靠地恢复或调度。
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
