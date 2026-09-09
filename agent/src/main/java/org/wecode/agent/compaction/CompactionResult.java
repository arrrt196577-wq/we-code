package org.wecode.agent.compaction;

/**
 * 一次上下文压缩尝试的非异常结果。
 * <p>
 * 本结果不携带触发来源，使手动命令、自动预压缩和 overflow 恢复能够复用同一执行器，
 * 并由各自的调用方决定提示、日志和重试策略。
 */
public sealed interface CompactionResult
        permits CompactionResult.Compacted, CompactionResult.NoNewContent, CompactionResult.StaleSnapshot {

    /**
     * 压缩检查点已原子写入的结果。
     *
     * @param compactsThroughSequence 本次摘要覆盖的最后会话消息序号
     */
    record Compacted(long compactsThroughSequence) implements CompactionResult {

        /** 校验成功结果必须指向一条已存在的正序号历史。 */
        public Compacted {
            // 覆盖终点不可能是零或负数，否则检查点不能替代任何历史。
            if (compactsThroughSequence <= 0) {
                throw new IllegalArgumentException("compactsThroughSequence must be > 0");
            }
        }
    }

    /** 当前检查点之后不存在可供摘要的新原始历史。 */
    record NoNewContent() implements CompactionResult {
    }

    /** 摘要模型调用期间会话发生变化，条件提交被安全拒绝。 */
    record StaleSnapshot() implements CompactionResult {
    }
}
