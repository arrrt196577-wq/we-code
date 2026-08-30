package org.wecode.agent.compaction;

import org.wecode.session.persistence.compaction.CompactionTranscript;
import org.wecode.session.persistence.entity.ToolExecutionStatus;

import java.util.Objects;

/**
 * 将结构化会话历史转换为摘要模型可阅读的带标签文本。
 * <p>
 * 序列化仅改变展示形式，不改变条目顺序；工具调用和工具结果始终跟随其来源 assistant 条目输出。
 */
public final class CompactionTranscriptSerializer {

    /** 与本地 OpenCode 一致的单条工具结果正文上限，按 Unicode 码点计算。 */
    public static final int TOOL_RESULT_MAX_CODE_POINTS = 2_000;

    /**
     * 将 transcript 的原始历史序列化为摘要输入文本。
     *
     * @param transcript 已按会话序号排序的摘要源
     * @return 带 USER、ASSISTANT、reasoning 和工具标签的历史文本
     */
    public String serialize(CompactionTranscript transcript) {
        Objects.requireNonNull(transcript, "transcript");
        StringBuilder serialized = new StringBuilder();
        for (CompactionTranscript.Entry entry : transcript.entries()) {
            // 条目之间保留空行，避免摘要模型把不同历史事实拼接为同一段。
            if (!serialized.isEmpty()) {
                serialized.append("\n\n");
            }
            appendEntry(serialized, entry);
        }
        return serialized.toString();
    }

    /** 根据条目类型追加相应角色文本。 */
    private void appendEntry(StringBuilder serialized, CompactionTranscript.Entry entry) {
        if (entry instanceof CompactionTranscript.UserEntry userEntry) {
            serialized.append("[User]: ").append(userEntry.content());
            return;
        }
        if (entry instanceof CompactionTranscript.AssistantEntry assistantEntry) {
            appendAssistantEntry(serialized, assistantEntry);
            return;
        }
        // sealed interface 新增实现时必须同步补充序列化规则，禁止静默遗漏历史。
        throw new IllegalStateException("Unsupported compaction transcript entry: " + entry.getClass().getName());
    }

    /** 追加 assistant 正文、reasoning 和与其绑定的工具调用/结果。 */
    private void appendAssistantEntry(
            StringBuilder serialized,
            CompactionTranscript.AssistantEntry assistantEntry
    ) {
        boolean hasPreviousPart = false;
        if (assistantEntry.content() != null && !assistantEntry.content().isBlank()) {
            serialized.append("[Assistant]: ").append(assistantEntry.content());
            hasPreviousPart = true;
        }
        if (assistantEntry.reasoningContent() != null && !assistantEntry.reasoningContent().isBlank()) {
            appendPartSeparator(serialized, hasPreviousPart);
            serialized.append("[Assistant reasoning]: ").append(assistantEntry.reasoningContent());
            hasPreviousPart = true;
        }
        for (CompactionTranscript.ToolExecutionEntry toolExecution : assistantEntry.toolExecutions()) {
            appendPartSeparator(serialized, hasPreviousPart);
            serialized.append("[Assistant tool call]: ")
                    .append(toolExecution.name())
                    .append('(')
                    .append(toolExecution.argumentsJson())
                    .append(')');
            appendPartSeparator(serialized, true);
            if (toolExecution.status() == ToolExecutionStatus.SUCCEEDED) {
                serialized.append("[Tool result]: ");
            } else if (toolExecution.status() == ToolExecutionStatus.FAILED) {
                serialized.append("[Tool error]: ");
            } else {
                // transcript 构造器已限制终态；此分支防止未来状态扩展后错误标记为成功。
                throw new IllegalStateException("Unsupported compaction tool status: " + toolExecution.status());
            }
            serialized.append(truncateToolResult(toolExecution.resultContent()));
            hasPreviousPart = true;
        }
    }

    /** 在同一个 assistant 条目的不同事实之间追加换行。 */
    private static void appendPartSeparator(StringBuilder serialized, boolean hasPreviousPart) {
        // 首个部分前不产生多余空行。
        if (hasPreviousPart) {
            serialized.append('\n');
        }
    }

    /** 按 Unicode 码点截断超长工具结果，避免 UTF-16 代理对被切断。 */
    private static String truncateToolResult(String resultContent) {
        int codePointCount = resultContent.codePointCount(0, resultContent.length());
        // 在限制以内时保持工具 observation 原文。
        if (codePointCount <= TOOL_RESULT_MAX_CODE_POINTS) {
            return resultContent;
        }
        int endOffset = resultContent.offsetByCodePoints(0, TOOL_RESULT_MAX_CODE_POINTS);
        return resultContent.substring(0, endOffset) + "\n[truncated]";
    }
}
