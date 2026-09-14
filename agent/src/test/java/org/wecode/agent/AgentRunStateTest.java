package org.wecode.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证 AgentRunState 的阶段专属数据和跨字段进度约束。 */
class AgentRunStateTest {

    /** 验证初始状态具有稳定的零值进度和 Starting 阶段。 */
    @Test
    void createsStartingState() {
        AgentRunState state = AgentRunState.starting("run-1");

        assertAll(
                () -> assertEquals("run-1", state.runId()),
                () -> assertEquals(0L, state.stateVersion()),
                () -> assertEquals(0, state.completedModelCalls()),
                () -> assertEquals("", state.lastAssistantContent()),
                () -> assertInstanceOf(AgentRunState.Starting.class, state.phase())
        );
    }

    /** 验证顶层状态拒绝无法关联或不可能出现的基础字段。 */
    @Test
    void rejectsInvalidTopLevelFields() {
        assertAll(
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new AgentRunState(null, 0, 0, "", new AgentRunState.Starting())
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> AgentRunState.starting("   ")
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new AgentRunState("run-1", -1, 0, "", new AgentRunState.Starting())
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new AgentRunState("run-1", 0, -1, "", new AgentRunState.Starting())
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new AgentRunState("run-1", 0, 0, "   ", new AgentRunState.Starting())
                ),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new AgentRunState("run-1", 0, 0, "", null)
                )
        );
    }

    /** 验证上下文准备、有效上下文和压缩阶段拒绝不可能的尝试次数。 */
    @Test
    void validatesContextPreparationProgress() {
        ContextWindowUsage usage = usage(false);

        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new AgentRunState.PreparingContext(0, 0)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new AgentRunState.PreparingContext(1, 1)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new AgentRunState.ContextReady(1, 1, usage)
                ),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new AgentRunState.ContextReady(1, 0, null)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new AgentRunState.Compacting(1, 2)
                )
        );
    }

    /** 验证模型调用阶段必须指向已完成模型调用之后的下一个 Step。 */
    @Test
    void validatesCallingModelStepNumber() {
        AgentRunState state = new AgentRunState(
                "run-1",
                3,
                1,
                "已有回复",
                new AgentRunState.CallingModel(2)
        );

        assertAll(
                () -> assertEquals(2, ((AgentRunState.CallingModel) state.phase()).stepNumber()),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new AgentRunState(
                                "run-1",
                                3,
                                1,
                                "已有回复",
                                new AgentRunState.CallingModel(1)
                        )
                )
        );
    }

    /** 验证工具阶段只接受已经完成模型调用的 Step 和合法游标。 */
    @Test
    void validatesToolExecutionCursor() {
        AgentRunState state = new AgentRunState(
                "run-1",
                4,
                2,
                "已有回复",
                new AgentRunState.ExecutingTools(2, 3, 3)
        );

        assertAll(
                () -> assertEquals(3, ((AgentRunState.ExecutingTools) state.phase()).nextToolCallIndex()),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new AgentRunState(
                                "run-1",
                                4,
                                2,
                                "已有回复",
                                new AgentRunState.ExecutingTools(1, 3, 0)
                        )
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new AgentRunState.ExecutingTools(2, 0, 0)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new AgentRunState.ExecutingTools(2, 3, 4)
                )
        );
    }

    /** 验证有序终止结果的完成轮次数必须与运行状态一致。 */
    @Test
    void validatesOrderedTerminationProgress() {
        AgentRunResult result = AgentRunResult.maxSteps("已有回复", 2, 2);
        AgentRunState.Termination termination = new AgentRunState.OrderedTermination(result);

        AgentRunState completed = new AgentRunState(
                "run-1",
                8,
                2,
                "已有回复",
                new AgentRunState.Completed(termination)
        );

        assertAll(
                () -> assertInstanceOf(AgentRunState.Completed.class, completed.phase()),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> new AgentRunState(
                                "run-1",
                                8,
                                1,
                                "已有回复",
                                new AgentRunState.Finishing(termination)
                        )
                )
        );
    }

    /** 验证异常终止保留主异常且不伪造 AgentRunResult。 */
    @Test
    void preservesFailedTermination() {
        RuntimeException cause = new IllegalStateException("model failed");
        AgentRunState.FailedTermination termination = new AgentRunState.FailedTermination(cause);
        AgentRunState state = new AgentRunState(
                "run-1",
                2,
                0,
                "",
                new AgentRunState.Finishing(termination)
        );

        assertEquals(cause, termination.cause());
        assertInstanceOf(AgentRunState.Finishing.class, state.phase());
    }

    /** 构造满足 ContextWindowUsage 自校验条件的测试数据。 */
    private static ContextWindowUsage usage(boolean compactionRequired) {
        long estimated = compactionRequired ? 80 : 40;
        return new ContextWindowUsage(
                estimated,
                100,
                10,
                10,
                80,
                80 - estimated,
                compactionRequired
        );
    }
}
