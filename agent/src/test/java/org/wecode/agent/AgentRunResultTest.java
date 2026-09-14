package org.wecode.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证 AgentLoop 结构化运行结果的字段约束与便捷构造方法。 */
class AgentRunResultTest {

    /** 验证最终响应工厂保留模型正文、停止原因和已完成轮次。 */
    @Test
    void createsFinalResponse() {
        AgentRunResult result = AgentRunResult.finalResponse("任务完成", 2);

        assertAll(
                () -> assertEquals("任务完成", result.assistantContent()),
                () -> assertEquals(AgentStopReason.FINAL_RESPONSE, result.stopReason()),
                () -> assertEquals("", result.stopDetail()),
                () -> assertEquals(2, result.completedSteps()),
                () -> assertTrue(result.hasFinalResponse())
        );
    }

    /** 验证只有模型明确完成回复时才标记为最终响应。 */
    @Test
    void reportsFinalResponseOnlyForFinalStopReason() {
        AgentRunResult result = AgentRunResult.cancelled("已有内容", "user cancelled", 1);

        assertFalse(result.hasFinalResponse());
    }

    /** 验证所有引用字段都必须显式提供，避免空值语义泄漏给调用方。 */
    @Test
    void rejectsNullReferenceFields() {
        assertAll(
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new AgentRunResult(null, AgentStopReason.CANCELLED, "cancelled", 0)
                ),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new AgentRunResult("", null, "cancelled", 0)
                ),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new AgentRunResult("", AgentStopReason.CANCELLED, null, 0)
                )
        );
    }

    /** 验证已完成轮次不能是负数。 */
    @Test
    void rejectsNegativeCompletedSteps() {
        assertThrows(
                IllegalArgumentException.class,
                () -> AgentRunResult.cancelled("", "cancelled", -1)
        );
    }

    /** 验证最终响应必须带有非空白正文。 */
    @Test
    void rejectsBlankFinalResponseContent() {
        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> AgentRunResult.finalResponse("", 1)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> AgentRunResult.finalResponse("   ", 1)
                )
        );
    }

    /** 验证最终响应必须来自至少一次已完成的模型调用。 */
    @Test
    void rejectsFinalResponseWithoutCompletedStep() {
        assertThrows(
                IllegalArgumentException.class,
                () -> AgentRunResult.finalResponse("完成", 0)
        );
    }

    /** 验证运行前拒绝不能携带已经完成的模型轮次。 */
    @Test
    void rejectsRunRejectionAfterCompletedStep() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new AgentRunResult("", AgentStopReason.RUN_REJECTED, "policy rejected", 1)
        );
    }

    /** 验证运行前拒绝必须保留非空白原因。 */
    @Test
    void rejectsRunRejectionWithoutDetail() {
        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> AgentRunResult.runRejected("")
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> AgentRunResult.runRejected("   ")
                )
        );
    }

    /** 验证达到最大轮次时允许尚未产生可展示的 assistant 正文。 */
    @Test
    void allowsMaxStepsWithoutAssistantContent() {
        AgentRunResult result = AgentRunResult.maxSteps("", 3, 3);

        assertAll(
                () -> assertEquals("", result.assistantContent()),
                () -> assertEquals(AgentStopReason.MAX_STEPS, result.stopReason()),
                () -> assertEquals("maxSteps=3", result.stopDetail()),
                () -> assertEquals(3, result.completedSteps())
        );
    }

    /** 验证输出截断结果保留部分模型正文和稳定的诊断信息。 */
    @Test
    void preservesTruncatedModelContent() {
        AgentRunResult result = AgentRunResult.modelOutputTruncated("未完成的内容", 1);

        assertAll(
                () -> assertEquals("未完成的内容", result.assistantContent()),
                () -> assertEquals(AgentStopReason.MODEL_OUTPUT_TRUNCATED, result.stopReason()),
                () -> assertEquals("finishReason=LENGTH", result.stopDetail()),
                () -> assertEquals(1, result.completedSteps())
        );
    }

    /** 验证最大轮次工厂拒绝非正数配置。 */
    @Test
    void rejectsNonPositiveMaxSteps() {
        assertAll(
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> AgentRunResult.maxSteps("", 0, 0)
                ),
                () -> assertThrows(
                        IllegalArgumentException.class,
                        () -> AgentRunResult.maxSteps("", -1, -1)
                )
        );
    }

    /** 验证达到上限时已完成轮次必须与最大轮次一致。 */
    @Test
    void rejectsCompletedStepsDifferentFromMaxSteps() {
        assertThrows(
                IllegalArgumentException.class,
                () -> AgentRunResult.maxSteps("已有内容", 2, 3)
        );
    }

    /** 验证取消可以发生在首次模型调用之前或若干轮次之后。 */
    @Test
    void allowsCancellationAtSafeRunBoundaries() {
        AgentRunResult beforeFirstStep = AgentRunResult.cancelled("", "user cancelled", 0);
        AgentRunResult afterSteps = AgentRunResult.cancelled("已有内容", "host cancelled", 2);

        assertAll(
                () -> assertEquals(0, beforeFirstStep.completedSteps()),
                () -> assertEquals(2, afterSteps.completedSteps()),
                () -> assertEquals(AgentStopReason.CANCELLED, afterSteps.stopReason()),
                () -> assertEquals("host cancelled", afterSteps.stopDetail())
        );
    }

    /** 验证错误与拒绝工厂生成互不混淆的停止事实。 */
    @Test
    void createsModelErrorAndRunRejection() {
        AgentRunResult modelError = AgentRunResult.modelReportedError("", "provider error", 1);
        AgentRunResult rejection = AgentRunResult.runRejected("workspace rejected");

        assertAll(
                () -> assertEquals(AgentStopReason.MODEL_REPORTED_ERROR, modelError.stopReason()),
                () -> assertEquals("provider error", modelError.stopDetail()),
                () -> assertEquals(1, modelError.completedSteps()),
                () -> assertEquals(AgentStopReason.RUN_REJECTED, rejection.stopReason()),
                () -> assertEquals("workspace rejected", rejection.stopDetail()),
                () -> assertEquals(0, rejection.completedSteps())
        );
    }
}
