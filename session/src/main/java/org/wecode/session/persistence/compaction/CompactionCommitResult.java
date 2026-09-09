package org.wecode.session.persistence.compaction;

/**
 * 条件写入上下文压缩检查点的结果。
 * <p>
 * 过期快照不是异常：它表示模型生成期间会话历史已经变化，调用方应丢弃本次摘要而非覆盖新历史。
 */
public sealed interface CompactionCommitResult
        permits CompactionCommitResult.Committed, CompactionCommitResult.StaleSnapshot {

    /**
     * @return 条件写入是否成功提交
     */
    boolean committed();

    /**
     * 已成功提交的检查点结果。
     *
     * @param compactsThroughSequence 新检查点覆盖到的最后历史序号
     */
    record Committed(long compactsThroughSequence) implements CompactionCommitResult {

        /** 验证成功检查点一定覆盖至少一条早于自身的历史。 */
        public Committed {
            if (compactsThroughSequence <= 0) {
                throw new IllegalArgumentException("compactsThroughSequence must be > 0");
            }
        }

        @Override
        public boolean committed() {
            return true;
        }
    }

    /** 读取快照后会话已变化，检查点未写入。 */
    record StaleSnapshot() implements CompactionCommitResult {

        @Override
        public boolean committed() {
            return false;
        }
    }
}
