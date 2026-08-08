package org.wecode.session.persistence.entity;

import org.wecode.llm.model.Role;

import java.util.Objects;

/**
 * {@code messages} 表的一条追加式消息记录。
 * <p>
 * {@code seq} 是会话内消息的唯一顺序；{@code agentRoundNo} 是一次 LLM 调用及其
 * 全部工具结果共享的轮次号，两者不能互相替代。
 *
 * @param sessionId    所属会话标识
 * @param seq          会话内从 1 开始的消息序号
 * @param agentRoundNo Agent 的 LLM 轮次号；SYSTEM 和 USER 消息为 {@code null}
 * @param role         与现有 {@link Role} 保持一致的消息角色
 * @param content      消息文本；SYSTEM 和 USER 必须非空，TOOL 必须非 null，ASSISTANT 可为空
 * @param toolCallId   TOOL 消息响应的工具调用 ID；其他角色为 {@code null}
 * @param createdAt    写入时间，UTC epoch milliseconds
 * @param payloadJson  消息级扩展 JSON 对象，约定包含格式版本及 toolCalls 等角色特有数据
 */
public record MessageRecord(
        String sessionId,
        long seq,
        Long agentRoundNo,
        Role role,
        String content,
        String toolCallId,
        long createdAt,
        String payloadJson
) {

    /**
     * 校验消息排序、轮次以及角色相关字段，防止无效记录进入持久化层。
     */
    public MessageRecord {
        sessionId = requireNonBlank(sessionId, "sessionId");
        role = Objects.requireNonNull(role, "role");
        payloadJson = requireNonBlank(payloadJson, "payloadJson");

        // 消息序号从 1 开始，零仅属于 Session 中的空会话状态。
        if (seq <= 0) {
            throw new IllegalArgumentException("seq must be > 0");
        }
        // Agent 轮次号存在时同样从 1 开始。
        if (agentRoundNo != null && agentRoundNo <= 0) {
            throw new IllegalArgumentException("agentRoundNo must be > 0 when present");
        }
        // 持久化时间统一采用非负 UTC epoch milliseconds。
        if (createdAt < 0) {
            throw new IllegalArgumentException("createdAt must be >= 0");
        }

        validateRoleFields(role, agentRoundNo, content, toolCallId);
    }

    /**
     * 按消息角色校验轮次、正文和工具调用关联字段。
     *
     * @param role         当前消息角色
     * @param agentRoundNo 当前消息所属的 Agent 轮次
     * @param content      当前消息正文
     * @param toolCallId   当前消息关联的工具调用标识
     */
    private static void validateRoleFields(Role role, Long agentRoundNo, String content, String toolCallId) {
        switch (role) {
            case SYSTEM, USER -> {
                // 系统与用户消息属于下一次模型调用的上下文，不归入既有 Agent 轮次。
                if (agentRoundNo != null) {
                    throw new IllegalArgumentException(role + " must not have agentRoundNo");
                }
                if (content == null || content.isBlank()) {
                    throw new IllegalArgumentException(role + " content must not be blank");
                }
                if (toolCallId != null) {
                    throw new IllegalArgumentException(role + " must not have toolCallId");
                }
            }
            case ASSISTANT -> {
                // assistant 响应总是由一次 LLM 调用产生，必须归属一个 Agent 轮次。
                if (agentRoundNo == null) {
                    throw new IllegalArgumentException("ASSISTANT must have agentRoundNo");
                }
                if (toolCallId != null) {
                    throw new IllegalArgumentException("ASSISTANT must not have toolCallId");
                }
            }
            case TOOL -> {
                // 工具结果必须能回关联到当轮 assistant 发起的具体调用。
                if (agentRoundNo == null) {
                    throw new IllegalArgumentException("TOOL must have agentRoundNo");
                }
                if (content == null) {
                    throw new IllegalArgumentException("TOOL content must not be null");
                }
                if (toolCallId == null || toolCallId.isBlank()) {
                    throw new IllegalArgumentException("TOOL toolCallId must not be blank");
                }
            }
        }
    }

    /**
     * 校验必须写入的文本字段。
     *
     * @param value     待校验的字段值
     * @param fieldName 字段名称，用于异常信息
     * @return 已校验的原始字段值
     */
    private static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        // 空白值不能作为会话标识或 JSON 内容写入持久化表。
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
