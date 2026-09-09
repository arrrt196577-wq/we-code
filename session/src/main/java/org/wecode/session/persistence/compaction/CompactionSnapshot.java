package org.wecode.session.persistence.compaction;

import org.wecode.session.persistence.entity.SessionStatus;

import java.util.Objects;

/**
 * 在一次短暂数据库读取中取得的上下文压缩稳定快照。
 * <p>
 * 快照中的版本号和最后序号用于在模型调用结束后执行条件提交；调用方不得在持有数据库会话时调用模型。
 *
 * @param sessionId               会话标识
 * @param expectedSessionVersion  读取快照时的会话乐观锁版本
 * @param expectedLastSequenceNo  读取快照时的最后历史事件序号
 * @param compactsThroughSequence 本次新检查点将覆盖到的历史序号
 * @param sessionStatus           读取快照时的会话状态，成功提交后保持该状态
 * @param transcript              供摘要模型使用的结构化历史
 */
public record CompactionSnapshot(
        String sessionId,
        long expectedSessionVersion,
        long expectedLastSequenceNo,
        long compactsThroughSequence,
        SessionStatus sessionStatus,
        CompactionTranscript transcript
) {

    /** 验证快照可用于生成摘要，并且其压缩边界与条件提交边界一致。 */
    public CompactionSnapshot {
        sessionId = requireNonBlank(sessionId, "sessionId");
        // sessions 表中的版本与序号均从零开始，不能接受损坏的负值快照。
        if (expectedSessionVersion < 0) {
            throw new IllegalArgumentException("expectedSessionVersion must be >= 0");
        }
        if (expectedLastSequenceNo < 0) {
            throw new IllegalArgumentException("expectedLastSequenceNo must be >= 0");
        }
        // 第一期检查点覆盖读取时的全部历史，避免提交后留下与摘要重复的尾部。
        if (compactsThroughSequence != expectedLastSequenceNo) {
            throw new IllegalArgumentException(
                    "compactsThroughSequence must equal expectedLastSequenceNo"
            );
        }
        sessionStatus = Objects.requireNonNull(sessionStatus, "sessionStatus");
        transcript = Objects.requireNonNull(transcript, "transcript");
    }

    /** 验证标识不能为 null 或空白，避免将条件更新发往错误会话。 */
    private static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
