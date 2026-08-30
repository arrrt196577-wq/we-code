package org.wecode.agent.compaction;

import org.wecode.llm.chat.ChatModel;
import org.wecode.llm.chat.ChatRequestOptions;
import org.wecode.llm.model.FinishReason;
import org.wecode.llm.model.LlmResponse;
import org.wecode.llm.model.Message;
import org.wecode.llm.model.ToolSpec;
import org.wecode.session.persistence.compaction.CompactionTranscript;

import java.util.List;
import java.util.Objects;

/**
 * 使用当前 Provider/模型生成会话摘要，但不提交任何会话状态。
 */
public final class CompactionSummaryGenerator {

    /** 首期摘要输出预算；后续可提升为配置项，并保持不超过规划中的 4096 硬上限。 */
    public static final int DEFAULT_MAX_OUTPUT_TOKENS = 2_048;

    private final ChatModel chatModel;
    private final CompactionPromptBuilder promptBuilder;
    private final int maxOutputTokens;

    /** 使用默认提示词构造器和输出预算创建摘要生成器。 */
    public CompactionSummaryGenerator(ChatModel chatModel) {
        this(chatModel, new CompactionPromptBuilder(), DEFAULT_MAX_OUTPUT_TOKENS);
    }

    /**
     * @param chatModel       当前 session 选中的模型
     * @param promptBuilder   摘要提示词构造器
     * @param maxOutputTokens 摘要输出 token 上限；必须在 {@code 1..4096} 范围内
     */
    public CompactionSummaryGenerator(
            ChatModel chatModel,
            CompactionPromptBuilder promptBuilder,
            int maxOutputTokens
    ) {
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel");
        this.promptBuilder = Objects.requireNonNull(promptBuilder, "promptBuilder");
        // 首期不允许通过构造参数绕过已确定的 4096 token 硬上限。
        if (maxOutputTokens <= 0 || maxOutputTokens > 4_096) {
            throw new IllegalArgumentException("maxOutputTokens must be in range 1..4096");
        }
        this.maxOutputTokens = maxOutputTokens;
    }

    /**
     * 构造摘要请求并调用 LLM。
     *
     * @param transcript 当前压缩边界后的原始历史和可选旧摘要
     * @return 尚未持久化的结构化摘要
     */
    public GeneratedSummary generate(CompactionTranscript transcript) {
        Objects.requireNonNull(transcript, "transcript");
        List<Message> messages = promptBuilder.buildMessages(transcript);
        LlmResponse response = chatModel.chat(
                messages,
                List.<ToolSpec>of(),
                ChatRequestOptions.withMaxOutputTokens(maxOutputTokens)
        );
        return toGeneratedSummary(response);
    }

    /** 校验摘要调用的响应不能携带工具调用、截断结果或空正文。 */
    private static GeneratedSummary toGeneratedSummary(LlmResponse response) {
        Objects.requireNonNull(response, "response");
        // 摘要调用没有声明工具；出现工具调用代表模型没有遵守摘要协议。
        if (response.hasToolCalls()) {
            throw new IllegalStateException("Compaction summary response must not contain tool calls");
        }
        // 被输出上限截断的摘要可能缺失固定章节，不能提交为检查点。
        if (response.finishReason() == FinishReason.LENGTH) {
            throw new IllegalStateException("Compaction summary response was truncated by output limit");
        }
        return new GeneratedSummary(response.content());
    }
}
