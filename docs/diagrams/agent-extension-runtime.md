# Agent 横切扩展运行时（规划）

状态：运行状态类型已定义，以下 Runtime、扩展分发及迁移流程尚未接入 AgentLoop。当前行为仍以 [现有流程图](request-to-output-flow.md) 为准，契约见 [架构文档](../agent-lifecycle-hooks.md)。

## 职责与装配

```mermaid
flowchart LR
    APP[应用装配入口] --> EXT[AgentExtensions：注册后冻结]
    APP --> LOOP[AgentLoop：兼容入口]
    LOOP -->|每次 run 创建| RT[AgentRuntime]
    RT --> STATE[AgentRunState：唯一写入]
    RT -->|阶段 Context| EXT
    EXT --> MW[AgentMiddleware：实现多个 Hook]
    MW -->|类型化阶段 Result| RT
    RT --> CORE[模型、工具、上下文等核心服务]
    RT --> PORT[核心持久化 Port]
    PORT --> STORE[Session 持久化事实源]
    STORE -->|重新读取消息| RT
    RT --> EVENTS[LifecyclePublisher]
    EVENTS --> OBS[日志、指标、UI]
```

Hook 是类型化阶段入口，Middleware 是实现入口的组件；不再设置相互独立的 HookRuntime 和 PipelineRuntime。扩展不能直接修改 Run 状态，Lifecycle 不能驱动控制流。

## 主循环接入骨架

```mermaid
flowchart TD
    START[建立 Run] --> BR[beforeRun]
    BR --> PREP[核心准备上下文与 prepareContext]
    PREP --> CHECK[核心校验]
    CHECK --> BM[beforeModel]
    BM --> VALID[确认最终请求有效]
    VALID --> MODEL[调用模型并完成核心 assistant 持久化]
    MODEL --> AM[afterModel]
    AM --> TOOLS{还有工具调用}
    TOOLS -->|有| BT[beforeTool]
    BT --> TC[核心校验、领取、执行、结果持久化]
    TC --> AT[afterTool]
    AT --> TOOLS
    TOOLS -->|无| AS[afterStep]
    AS --> NEXT{核心判断继续}
    NEXT -->|是| PREP
    NEXT -->|否| FIN[统一收口]
    FIN --> AR[afterRun]
    AR --> END[完成状态与最终事实事件]
    FAIL[任一阶段停止、异常或取消] --> FIN
```

图中 Hook 是目标位置，具体 Context/Result 随入口接入确定。提前停止或失败后不得继续普通执行链；收口防重复，清理失败不得掩盖原异常。afterTool 的加工与存储协议不在本轮确定。请求或工具输入若允许加工，核心必须校验最终有效值。

## 状态迁移约束

```mermaid
stateDiagram-v2
    [*] --> Starting
    Starting --> PreparingContext: 准入通过
    Starting --> Finishing: 拒绝或失败
    PreparingContext --> ContextReady: 请求准备完成
    PreparingContext --> Finishing: 失败或取消
    ContextReady --> CallingModel: 核心确认可调用
    ContextReady --> Compacting: 后续自动压缩能力
    ContextReady --> Finishing: 停止或失败
    Compacting --> PreparingContext: 压缩成功后重建
    Compacting --> Finishing: 无法继续
    CallingModel --> ExecutingTools: 响应含工具
    CallingModel --> Finishing: 最终响应或失败
    ExecutingTools --> PreparingContext: 本轮完成并继续
    ExecutingTools --> Finishing: 结束或失败
    Finishing --> Completed: 收口完成
    Completed --> [*]
```

该图为粗粒度目标阶段关系，不是完整迁移 API；取消和异常统一进入 Finishing。现有类型只保证组合合法，后续还需定义迁移方法及阶段入口的精确位置。stateVersion 是 Run 内迁移版本，不是持久化或上下文内容版本。Compacting 已有类型，自动压缩尚未接入，也不是本轮框架接入的前提。
