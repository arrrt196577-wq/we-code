package org.wecode.session.persistence.compaction;

import org.wecode.session.persistence.entity.ToolExecutionStatus;

import java.util.List;
import java.util.Objects;

/**
 * 生成上下文摘要时使用的只读会话历史。
 * <p>
 * 原始历史仍由持久化层保存；本对象仅以摘要所需的结构暴露当前压缩边界后的 USER、ASSISTANT
 * 与工具事实，不携带固定 system prompt。
 *
 * @param previousSummary 最新压缩检查点的摘要；首次压缩时为 {@code null}
 * @param entries         按会话序号升序排列的原始历史条目
 */
public record CompactionTranscript(String previousSummary, List<Entry> entries) {

    /** 校验摘要输入的历史顺序和可选旧摘要。 */
    public CompactionTranscript {
        // 已存在检查点时必须提供可用摘要；首次压缩允许为空。
        if (previousSummary != null && previousSummary.isBlank()) {
            throw new IllegalArgumentException("previousSummary must not be blank when present");
        }
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        long previousSequenceNo = 0L;
        for (Entry entry : entries) {
            Objects.requireNonNull(entry, "entries element");
            // 会话序号必须严格递增，避免摘要提示词重排历史。
            if (entry.sequenceNo() <= previousSequenceNo) {
                throw new IllegalArgumentException("entries must be ordered by increasing sequenceNo");
            }
            previousSequenceNo = entry.sequenceNo();
        }
    }

    /**
     * @return 是否存在可供本次摘要处理的新原始历史
     */
    public boolean hasEntries() {
        return !entries.isEmpty();
    }

    /** 上下文摘要的原始历史条目。 */
    public sealed interface Entry permits UserEntry, AssistantEntry {

        /**
         * @return 会话内不可变的消息序号
         */
        long sequenceNo();
    }

    /**
     * 用户输入的摘要源条目。
     *
     * @param sequenceNo 会话内消息序号
     * @param content    用户原始文本
     */
    public record UserEntry(long sequenceNo, String content) implements Entry {

        /** 校验用户条目携带可摘要的原始文本。 */
        public UserEntry {
            requirePositiveSequenceNo(sequenceNo);
            content = requireNonBlank(content, "content");
        }
    }

    /**
     * assistant 响应及其按调用顺序排列的工具执行事实。
     *
     * @param sequenceNo       会话内 assistant 消息序号
     * @param content          assistant 可见正文；仅工具调用时可为 {@code null}
     * @param reasoningContent Provider 返回的 reasoning 文本；没有时为 {@code null}
     * @param toolExecutions   与该 assistant 消息关联的终态工具执行记录
     */
    public record AssistantEntry(
            long sequenceNo,
            String content,
            String reasoningContent,
            List<ToolExecutionEntry> toolExecutions
    ) implements Entry {

        /** 校验 assistant 条目可由正文或工具执行事实构成。 */
        public AssistantEntry {
            requirePositiveSequenceNo(sequenceNo);
            toolExecutions = List.copyOf(Objects.requireNonNull(toolExecutions, "toolExecutions"));
            for (ToolExecutionEntry toolExecution : toolExecutions) {
                Objects.requireNonNull(toolExecution, "toolExecutions element");
            }
            // assistant 不能既没有可见正文，也没有可供摘要的工具调用。
            if ((content == null || content.isBlank()) && toolExecutions.isEmpty()) {
                throw new IllegalArgumentException("assistant entry must have content or toolExecutions");
            }
        }
    }

    /**
     * 一次已经结束的工具调用及结果。
     *
     * @param callId        Provider 工具调用标识
     * @param name          工具名称
     * @param argumentsJson 调用参数 JSON 原文
     * @param status        终态执行状态，只能为 SUCCEEDED 或 FAILED
     * @param resultContent 工具返回的 observation 文本
     */
    public record ToolExecutionEntry(
            String callId,
            String name,
            String argumentsJson,
            ToolExecutionStatus status,
            String resultContent
    ) {

        /** 校验工具调用及其结果已经以终态事实落库。 */
        public ToolExecutionEntry {
            callId = requireNonBlank(callId, "callId");
            name = requireNonBlank(name, "name");
            argumentsJson = requireNonBlank(argumentsJson, "argumentsJson");
            status = Objects.requireNonNull(status, "status");
            resultContent = Objects.requireNonNull(resultContent, "resultContent");
            // 摘要不得猜测副作用不确定的工具结果。
            if (status != ToolExecutionStatus.SUCCEEDED && status != ToolExecutionStatus.FAILED) {
                throw new IllegalArgumentException("tool execution must be SUCCEEDED or FAILED");
            }
        }
    }

    /** 校验会话消息序号是有效正数。 */
    private static void requirePositiveSequenceNo(long sequenceNo) {
        // 零和负数不可能是已持久化会话消息的序号。
        if (sequenceNo <= 0) {
            throw new IllegalArgumentException("sequenceNo must be > 0");
        }
    }

    /** 校验摘要源必须携带非空白标识或文本。 */
    private static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        // 空白字段无法为摘要模型提供可核对的事实。
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
