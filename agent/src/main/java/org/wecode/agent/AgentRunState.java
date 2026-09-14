package org.wecode.agent;

import java.util.Objects;

/**
 * 一次 Agent Turn 的不可变控制状态。
 *
 * <p>该类型只供 Agent 核心运行时持有，不直接暴露给 Hook、Middleware 或 Lifecycle Subscriber。
 * 阶段专属数据由 {@link Phase} 的不同实现承载，避免使用可空字段表达互斥状态。</p>
 *
 * @param runId                本次运行的唯一标识
 * @param stateVersion         本次运行内部的状态迁移版本，从零开始
 * @param completedModelCalls  已完成的正常 Agent 模型调用数，不包含 compaction 模型调用
 * @param lastAssistantContent 最后一段非空 assistant 正文；尚未产生时为空字符串
 * @param phase                当前运行阶段及该阶段专属数据
 */
record AgentRunState(
        String runId,
        long stateVersion,
        int completedModelCalls,
        String lastAssistantContent,
        Phase phase
) {

    /** 校验控制状态内部字段及当前阶段之间保持一致。 */
    AgentRunState {
        runId = requireNonBlank(runId, "runId");
        lastAssistantContent = Objects.requireNonNull(lastAssistantContent, "lastAssistantContent");
        phase = Objects.requireNonNull(phase, "phase");

        // 状态版本从零开始，并且只能由后续状态机单调递增。
        if (stateVersion < 0) {
            throw new IllegalArgumentException("stateVersion must be >= 0");
        }
        // 正常模型调用次数不能为负；compaction 调用不计入该字段。
        if (completedModelCalls < 0) {
            throw new IllegalArgumentException("completedModelCalls must be >= 0");
        }
        // 该字段只保存最后一段非空正文，不能用纯空白伪装成有效内容。
        if (!lastAssistantContent.isEmpty() && lastAssistantContent.isBlank()) {
            throw new IllegalArgumentException("lastAssistantContent must be empty or non-blank");
        }

        validateProgress(phase, completedModelCalls);
    }

    /**
     * 创建尚未进入任何执行阶段的初始状态。
     *
     * @param runId 本次运行的唯一标识
     * @return 状态版本和模型调用计数均为零的初始状态
     */
    static AgentRunState starting(String runId) {
        // 初始状态不包含 assistant 正文，也尚未完成任何正常模型调用。
        return new AgentRunState(runId, 0L, 0, "", new Starting());
    }

    /** 根据阶段专属计数校验其与顶层模型调用进度一致。 */
    private static void validateProgress(Phase phase, int completedModelCalls) {
        // 调用模型时，本次 Step 必须紧接在已完成模型调用之后。
        if (phase instanceof CallingModel callingModel
                && callingModel.stepNumber() != (long) completedModelCalls + 1L) {
            throw new IllegalArgumentException(
                    "CallingModel stepNumber must equal completedModelCalls + 1"
            );
        }
        // 进入工具阶段代表本 Step 的模型调用已经完成并计入顶层进度。
        if (phase instanceof ExecutingTools executingTools
                && executingTools.stepNumber() != completedModelCalls) {
            throw new IllegalArgumentException(
                    "ExecutingTools stepNumber must equal completedModelCalls"
            );
        }
        // 有序结束结果必须与运行状态记录的模型调用次数一致。
        if (phase instanceof Finishing finishing) {
            validateTerminationProgress(finishing.termination(), completedModelCalls);
        }
        // 已完成状态同样不能携带属于其他执行进度的结果。
        if (phase instanceof Completed completed) {
            validateTerminationProgress(completed.termination(), completedModelCalls);
        }
    }

    /** 校验有序结束结果中的模型轮次数与当前运行进度一致。 */
    private static void validateTerminationProgress(Termination termination, int completedModelCalls) {
        // 异常终止没有结构化 AgentRunResult，因此只校验有序结束结果。
        if (termination instanceof OrderedTermination ordered
                && ordered.result().completedSteps() != completedModelCalls) {
            throw new IllegalArgumentException(
                    "termination completedSteps must equal completedModelCalls"
            );
        }
    }

    /** 校验运行标识等必填文本。 */
    private static String requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        // 空白标识不能稳定关联状态、Hook 决策和 Lifecycle 事件。
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }

    /** 当前运行阶段的封闭类型集合。 */
    sealed interface Phase permits
            Starting,
            PreparingContext,
            ContextReady,
            Compacting,
            CallingModel,
            ExecutingTools,
            Finishing,
            Completed {
    }

    /** Turn 已创建但尚未开始准备上下文。 */
    record Starting() implements Phase {
    }

    /**
     * 正在从持久化事实源加载并构造本轮有效上下文。
     *
     * @param preparationAttempt 当前上下文准备次数，从一开始
     * @param compactionAttempts  当前 Turn 已尝试的自动压缩次数
     */
    record PreparingContext(int preparationAttempt, int compactionAttempts) implements Phase {

        /** 校验准备次数可以覆盖所有已经发生的压缩后重建。 */
        PreparingContext {
            // 首次进入上下文准备阶段时次数必须为一。
            if (preparationAttempt < 1) {
                throw new IllegalArgumentException("preparationAttempt must be >= 1");
            }
            // 自动压缩次数不能为负。
            if (compactionAttempts < 0) {
                throw new IllegalArgumentException("compactionAttempts must be >= 0");
            }
            // 每次成功压缩后至少需要重新准备一次上下文。
            if (preparationAttempt < (long) compactionAttempts + 1L) {
                throw new IllegalArgumentException(
                        "preparationAttempt must be greater than compactionAttempts"
                );
            }
        }
    }

    /**
     * 有效请求已经完成数据变换、核心校验和窗口预算计算。
     *
     * @param preparationAttempt 产生当前有效上下文的准备次数
     * @param compactionAttempts 当前 Turn 已尝试的自动压缩次数
     * @param usage              根据最终有效请求计算的上下文窗口使用量
     */
    record ContextReady(
            int preparationAttempt,
            int compactionAttempts,
            ContextWindowUsage usage
    ) implements Phase {

        /** 校验当前有效上下文来自一次合法的准备过程。 */
        ContextReady {
            // 有效上下文至少经过一次准备。
            if (preparationAttempt < 1) {
                throw new IllegalArgumentException("preparationAttempt must be >= 1");
            }
            // 自动压缩次数不能为负。
            if (compactionAttempts < 0) {
                throw new IllegalArgumentException("compactionAttempts must be >= 0");
            }
            // 当前上下文必须是首次准备或某次压缩后重建的结果。
            if (preparationAttempt < (long) compactionAttempts + 1L) {
                throw new IllegalArgumentException(
                        "preparationAttempt must be greater than compactionAttempts"
                );
            }
            usage = Objects.requireNonNull(usage, "usage");
        }
    }

    /**
     * 正在执行自动上下文压缩核心子流程。
     *
     * @param preparationAttempt 触发本次压缩的上下文准备次数
     * @param compactionAttempt  本次自动压缩序号，从一开始
     */
    record Compacting(int preparationAttempt, int compactionAttempt) implements Phase {

        /** 校验压缩必须由已经发生的上下文准备触发。 */
        Compacting {
            // 没有完成首次上下文准备前不能启动压缩。
            if (preparationAttempt < 1) {
                throw new IllegalArgumentException("preparationAttempt must be >= 1");
            }
            // 自动压缩尝试使用从一开始的独立序号。
            if (compactionAttempt < 1) {
                throw new IllegalArgumentException("compactionAttempt must be >= 1");
            }
            // 同一次上下文准备不能凭空产生比准备次数更多的压缩尝试。
            if (compactionAttempt > preparationAttempt) {
                throw new IllegalArgumentException(
                        "compactionAttempt must not exceed preparationAttempt"
                );
            }
        }
    }

    /**
     * 已通过模型调用控制点，正在执行一次正常 Agent 模型调用。
     *
     * @param stepNumber 当前 Step 编号，从一开始
     */
    record CallingModel(int stepNumber) implements Phase {

        /** 校验 Step 编号使用从一开始的稳定序号。 */
        CallingModel {
            // 零和负数不能定位一次实际模型调用。
            if (stepNumber < 1) {
                throw new IllegalArgumentException("stepNumber must be >= 1");
            }
        }
    }

    /**
     * 当前 Step 的模型响应已完成，正在按顺序处理其工具调用。
     *
     * @param stepNumber        当前 Step 编号
     * @param totalToolCalls    本 Step 的工具调用总数
     * @param nextToolCallIndex 下一个待处理工具调用的零基下标；等于总数表示全部完成
     */
    record ExecutingTools(
            int stepNumber,
            int totalToolCalls,
            int nextToolCallIndex
    ) implements Phase {

        /** 校验工具游标始终落在当前模型响应定义的范围内。 */
        ExecutingTools {
            // 工具阶段只能属于一个已经开始的正数 Step。
            if (stepNumber < 1) {
                throw new IllegalArgumentException("stepNumber must be >= 1");
            }
            // 没有工具调用时不应进入工具执行阶段。
            if (totalToolCalls < 1) {
                throw new IllegalArgumentException("totalToolCalls must be >= 1");
            }
            // 下一个工具下标允许等于总数，用于表达刚刚处理完最后一个调用。
            if (nextToolCallIndex < 0 || nextToolCallIndex > totalToolCalls) {
                throw new IllegalArgumentException(
                        "nextToolCallIndex must be between 0 and totalToolCalls"
                );
            }
        }
    }

    /**
     * 已形成终止事实，正在完成 Turn 收口和 Lifecycle 发布。
     *
     * @param termination 有序结果或运行异常
     */
    record Finishing(Termination termination) implements Phase {

        /** 保证收口阶段始终携带明确终止事实。 */
        Finishing {
            termination = Objects.requireNonNull(termination, "termination");
        }
    }

    /**
     * Turn 已完成进程内收口。
     *
     * @param termination 最终有序结果或运行异常
     */
    record Completed(Termination termination) implements Phase {

        /** 保证完成状态始终可以解释本次运行如何结束。 */
        Completed {
            termination = Objects.requireNonNull(termination, "termination");
        }
    }

    /** Turn 收口时可出现的终止事实。 */
    sealed interface Termination permits OrderedTermination, FailedTermination {
    }

    /**
     * Agent 以结构化结果有序结束。
     *
     * @param result 有序运行结果
     */
    record OrderedTermination(AgentRunResult result) implements Termination {

        /** 禁止创建没有结构化结果的有序结束事实。 */
        OrderedTermination {
            result = Objects.requireNonNull(result, "result");
        }
    }

    /**
     * Agent 因运行异常结束。
     *
     * @param cause 保留的主异常
     */
    record FailedTermination(RuntimeException cause) implements Termination {

        /** 禁止以空异常伪造失败结束事实。 */
        FailedTermination {
            cause = Objects.requireNonNull(cause, "cause");
        }
    }
}
