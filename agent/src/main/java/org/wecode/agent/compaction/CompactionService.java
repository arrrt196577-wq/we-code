package org.wecode.agent.compaction;

import org.wecode.session.persistence.SessionConversationStore;
import org.wecode.session.persistence.compaction.CompactionCommitResult;
import org.wecode.session.persistence.compaction.CompactionSnapshot;

import java.util.Objects;

/**
 * 执行一次完整的上下文压缩：读取稳定快照、生成摘要并以条件写入提交检查点。
 * <p>
 * 本服务不感知手动、自动或 overflow 等触发来源。调用方只负责决定何时调用和如何处理结果，
 * 从而保证所有压缩路径复用同一份摘要与持久化语义。
 */
public final class CompactionService {

    /** 当前 Markdown 摘要协议和安全回灌语义对应的策略版本。 */
    public static final String STRATEGY_VERSION = "compaction-v1";

    private final SessionConversationStore conversationStore;
    private final CompactionSummaryGenerator summaryGenerator;
    private final SummaryFormatValidator summaryFormatValidator;

    /**
     * 使用生产默认的摘要格式校验器创建压缩服务。
     *
     * @param conversationStore 会话快照读取与条件检查点写入入口
     * @param summaryGenerator  基于当前 Provider/模型生成摘要的组件
     */
    public CompactionService(
            SessionConversationStore conversationStore,
            CompactionSummaryGenerator summaryGenerator
    ) {
        this(conversationStore, summaryGenerator, new SummaryFormatValidator());
    }

    /**
     * 使用指定格式校验器创建服务，仅供同包测试或后续协议版本演进注入。
     *
     * @param conversationStore       会话快照读取与条件检查点写入入口
     * @param summaryGenerator        摘要模型调用组件
     * @param summaryFormatValidator  摘要结构校验器
     */
    CompactionService(
            SessionConversationStore conversationStore,
            CompactionSummaryGenerator summaryGenerator,
            SummaryFormatValidator summaryFormatValidator
    ) {
        this.conversationStore = Objects.requireNonNull(conversationStore, "conversationStore");
        this.summaryGenerator = Objects.requireNonNull(summaryGenerator, "summaryGenerator");
        this.summaryFormatValidator = Objects.requireNonNull(summaryFormatValidator, "summaryFormatValidator");
    }

    /**
     * 压缩指定会话当前尚未被检查点覆盖的历史。
     * <p>
     * 数据库连接只用于读取快照和最终提交，模型调用期间不持有 SQLite 事务。若历史在模型调用期间
     * 发生变化，条件提交会返回过期快照而不是重读后静默覆盖。
     *
     * @param sessionId 待压缩的会话标识
     * @return 本次压缩的成功、无新增内容或过期快照结果
     */
    public CompactionResult compact(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");

        // 第一步：读取包含版本和序号的稳定快照，供后续 CAS 提交使用。
        CompactionSnapshot snapshot = conversationStore.loadCompactionSnapshot(sessionId);
        // 没有新增历史时不得浪费模型调用，也不能重复追加等价检查点。
        if (!snapshot.transcript().hasEntries()) {
            return new CompactionResult.NoNewContent();
        }

        // 第二步：在没有数据库事务的情况下调用摘要模型。
        GeneratedSummary generatedSummary = summaryGenerator.generate(snapshot.transcript());
        // 第三步：只有符合固定协议的摘要才允许成为长期会话记忆。
        summaryFormatValidator.validate(generatedSummary.content());

        // 第四步：使用读取快照时的版本和序号原子追加检查点。
        CompactionCommitResult commitResult = conversationStore.appendCompactionIfUnchanged(
                snapshot,
                generatedSummary.content(),
                STRATEGY_VERSION
        );
        if (commitResult instanceof CompactionCommitResult.Committed committed) {
            return new CompactionResult.Compacted(committed.compactsThroughSequence());
        }
        // 条件更新未命中表示会话已变化；禁止重新读取后直接使用旧摘要提交。
        return new CompactionResult.StaleSnapshot();
    }
}
