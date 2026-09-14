# Agent 运行时与阶段扩展架构规划

## 文档状态

- 已按“统一运行时 + 类型化阶段扩展”对齐，尚未接入 AgentLoop。
- 已定义 hook 模块的 HookPoint，以及 agent 中的 AgentRunState、AgentRunResult、AgentStopReason；运行状态和结构化结果尚未接入主循环。
- AgentRuntime、状态迁移 API、AgentSnapshot、AgentMiddleware、AgentExtensions 和 Lifecycle 发布机制尚未实现。
- 当前 AgentLoop 仍返回 String，使用 stepLogger，通过 AgentExecutionListener 同步维护持久化边界；预算超限仅记录观察信息，自动压缩尚未接入。
- 本轮确定框架结构与接入方式；afterTool 的具体加工和存储协议、自动压缩业务流程后续单独讨论。

对应图见 [运行时规划](diagrams/agent-extension-runtime.md) 和 [模块边界](diagrams/hook-module-dependencies.md)。

## 1. 目标与术语

统一管理一次 Agent Run 的状态，在固定阶段提供扩展入口，使新增功能集中在 Middleware 和应用装配处，不散落在主循环各处。

| 概念 | 职责 |
| --- | --- |
| AgentRuntime | 管理单次 Run 的状态、执行顺序、核心校验和异常收口 |
| Hook | 核心明确开放的类型化阶段方法，例如 beforeModel、afterTool |
| AgentMiddleware | 功能扩展组件，可以实现一个或多个 Hook 方法 |
| AgentExtensions | 统一注册、冻结扩展集合，按 Hook 分发调用 |
| Lifecycle | 单向发布已发生的状态变化和执行事实，供日志、指标和 UI 观察 |

Hook 不再限定为独立的控制决策链；Middleware 不再限定为只有 T → T 的纯数据函数。具体入口契约决定允许返回的数据变化与控制指令，Runtime 校验并应用结果。

不再同时建设独立的 Hook 决策注册框架和 Middleware Pipeline 注册框架。不使用 next()、任意跳转、万能 Result 或 Map<String, Object> 承载所有阶段。

## 2. AgentLoop 与 AgentRuntime

AgentLoop 保留对外入口并持有装配好的运行依赖，每次 run(...) 创建独立的 AgentRuntime。

```text
AgentLoop
└── 每次调用创建 AgentRuntime
    ├── AgentRunState
    ├── 冻结的 AgentExtensions
    ├── 消息来源、模型和工具接口
    └── 核心持久化与业务服务
```

Runtime 是运行状态唯一写入者。状态只能通过集中式合法迁移方法更新；不得使用全局可变阶段或向扩展暴露可写状态。核心服务承载上下文准备、模型调用和工具执行等业务，Runtime 协调这些服务。

兼容接入阶段，公开 run(...) 暂时保留 String 返回方式；内部使用 AgentRunResult，由入口适配原有输出。意外异常保留原异常和调用栈，不伪装为正常 Result。

### 状态与数据分层

- Session 数据库是消息、工具执行和压缩检查点的可恢复事实源。
- AgentRunState 是单次用户输入触发的 Run 的控制快照，不是整个 Session 或运行历史。
- 扩展获得只读 AgentSnapshot 和阶段专用 Context；集合及嵌套数据也必须受控，不能只依赖 record 的浅层不可变性。
- 完整请求、消息历史和工具结果由阶段局部数据承载，不复制进长期运行状态。

当前已定义的状态：

```text
AgentRunState
├── runId / stateVersion
├── completedModelCalls / lastAssistantContent
└── Phase
    ├── Starting
    ├── PreparingContext(preparationAttempt, compactionAttempts)
    ├── ContextReady(preparationAttempt, compactionAttempts, usage)
    ├── Compacting(preparationAttempt, compactionAttempt)
    ├── CallingModel(stepNumber)
    ├── ExecutingTools(stepNumber, totalToolCalls, nextToolCallIndex)
    ├── Finishing(Termination)
    └── Completed(Termination)

Termination = OrderedTermination(AgentRunResult)
            | FailedTermination(RuntimeException)
```

当前构造校验只保证字段组合合法，不能保证合法前置状态，后续迁移 API 必须补足。stateVersion 是 Run 内迁移版本，不是 Session 数据库版本，也不能直接当作上下文内容版本。

一个 Step 包含一次正常模型调用及其产生的工具调用；上下文准备、压缩不消耗正常 maxSteps。接入前需统一 completedModelCalls 与 AgentRunResult.completedSteps 的计数语义，明确失败调用与未完成工具周期的计数，并核对现有组合不变量。Run/Turn 命名及事件命名也应在接入前统一。

## 3. Middleware 与类型化 Hook

第一版在 agent 内使用明确的 Java 接口，不先建设动态扩展点或通用分发引擎。以下仅示意接口形状，Context/Result 尚未实现，具体字段和指令逐入口确定：

```java
public interface AgentMiddleware {

    /** 根据模型调用前的只读上下文返回该入口允许的结果。 */
    default BeforeModelResult beforeModel(BeforeModelContext context) {
        // 未实现该入口时保持默认行为。
        return BeforeModelResult.proceed();
    }

    /** 根据工具完成上下文返回该入口允许的结果。 */
    default AfterToolResult afterTool(AfterToolContext context) {
        // 未实现该入口时保持默认行为，具体加工契约后续确定。
        return AfterToolResult.unchanged();
    }
}
```

一个功能组件可以实现多个入口，只注册一次；框架负责在相应阶段调用。Middleware 不直接修改 Run 状态、不自行推进主循环、不绕过核心持久化和校验。普通日志观察使用 Lifecycle。

| Hook 方法 | 调用位置 | 契约方向 |
| --- | --- | --- |
| beforeRun | Run 建立后、主要工作开始前 | 准入控制 |
| prepareContext | 上下文准备期间 | 受控上下文加工 |
| beforeModel | 模型调用之前 | 请求相关扩展与调用控制 |
| afterModel | 响应及核心 assistant 持久化完成后 | 响应后处理，具体能力待定 |
| beforeTool | 工具领取及执行之前 | 工具调用检查 |
| afterTool | 工具执行及核心结果持久化完成后 | 后处理入口，具体能力待定 |
| afterStep | 本轮模型与工具处理完成后 | 后续继续策略 |
| afterRun | Run 收口期间 | 扩展清理，普通观察走 Lifecycle |

这些是目标挂载位置，不代表当前都已具备完整业务协议。开放一个入口前必须确定 Context、Result、允许动作及失败行为；不预先允许任意数据修改或重试。

## 4. 统一装配与分发

```text
应用装配入口
→ 注册 Middleware A / B / C
→ 构建并冻结 AgentExtensions
→ 注入 AgentLoop
→ 每个 Runtime 在固定位置调用 extensions.beforeModel(context) 等方法
```

新增已有入口上的功能，只需新增 Middleware 并注册，不修改主循环；新增执行位置，需要核心显式开放入口。

第一版规则：

- 按显式注册顺序执行，不引入注解扫描、数字优先级或 before/after 依赖排序。
- 构建时校验 Middleware 标识唯一，运行时禁止增删和重排。
- 无扩展和默认实现保持现有行为。
- 结果不能为 null；异常附带 Middleware 标识、Hook 名称和原始原因，交给 Runtime 收口。
- 每个 Hook 分别定义数据合并、后续组件可见数据和停止短路规则；不沿用全局“首个非继续结果生效”，也不先实现通用 reducer。
- 控制指令由 Runtime 校验、应用，不得降级核心安全约束。
- 共享 Middleware 实例不得保存单次 Run 的可变状态；冻结注册集合不等于实现本身线程安全。
- 进程内 Middleware 是受信任扩展，类型化接口不构成安全沙箱。

## 5. 接入主循环的骨架

```text
建立 Run 状态
→ beforeRun

每个 Step：
    核心准备上下文
    → prepareContext
    → 核心校验
    → beforeModel
    → 核心确认最终请求有效
    → 调用模型
    → 核心 assistant 持久化
    → afterModel

    对每个工具：
        beforeTool
        → 核心确认有效调用满足校验
        → 核心领取、执行、结果持久化
        → afterTool

    → afterStep
    → 核心判断继续或结束

统一收口（正常、停止、异常、取消）
→ afterRun
→ 完成状态与最终事实事件
→ 返回结果或抛出异常
```

该图表达顺序，不表示每条路径都会调用所有入口；提前停止或失败后不能继续普通执行链。无工具调用、步数耗尽、取消和异常必须进入统一收口。afterRun 的清理失败不得掩盖原始失败；收口须防重复，具体清理失败聚合规则在接入时明确。完成事件在最终状态确定后发布。

如果 beforeModel 后续允许修改请求，必须在其完成后重新校验协议和最终 token 预算；工具输入同理。安全性不能依赖扩展注册顺序。

## 6. Lifecycle 与核心边界

Lifecycle 由 Runtime 在状态变化或核心事实完成后发布，不用于驱动主循环。事件携带运行标识、状态版本、Run 内递增事件序号及适用的领域结果。事件名称与字段集合随迁移 API 确定，不能机械地为每个 Hook 创建同名事件。

- 日志、指标、UI Subscriber 采用 best-effort；单个失败不阻断 Agent 或其他 Subscriber。
- Subscriber 失败通过独立错误报告入口记录，避免递归发布事件。
- 在进程可继续运行时，正常、拒绝、取消和异常路径都应有完成事实；不承诺崩溃时必达。
- stepLogger 后续迁移为日志 Subscriber。
- AgentExecutionListener 当前承担 assistant、工具领取及结果的同步持久化顺序，必须保留为核心 Port，不降级为普通 Middleware 或 Subscriber。

必须保留：assistant 及待执行工具先落库再执行工具；确定性工具结果先落库再进入下一轮；持久化失败不能伪装成功；工具副作用未知不能伪装成未执行。扩展失败不回滚已经发生的真实副作用。SessionStatus.IDLE 只代表运行收口，不代表用户业务目标成功。

## 7. 模块边界

```text
agent
├── AgentLoop / AgentRuntime / AgentRunState / AgentSnapshot
├── AgentMiddleware / AgentExtensions
├── 各阶段 Context / Result 与领域 Outcome
├── LifecyclePublisher 与事件
└── 核心业务服务及持久化 Port

hook（保留现状）
└── HookPoint<C, R>
```

暂不继续实现旧规划中的 HookHandler、HookRegistry、HookRuntime，也不创建独立 Pipeline Registry/Runtime 或 pipeline 模块。现有 HookPoint 和 Maven 依赖先保留，但新的 Agent 阶段方法不必通过它分发；其未来用途待真实复用需求再评估。

## 8. 渐进实施与验证

1. **迁移运行骨架**：统一计数语义，定义 Snapshot、合法状态迁移与必要结果类型；每次 run 创建 Runtime，保持现有消息读取、模型调用、工具执行和持久化顺序。
2. **接入扩展分发**：实现 AgentMiddleware、AgentExtensions 和已明确契约的首批入口；默认实现无操作，空扩展保持兼容。配合状态迁移接入 Lifecycle，再迁移日志。
3. **验证框架，再增加业务扩展**：验证阶段顺序、Run 隔离、停止后无后续副作用、扩展异常和取消收口、持久化失败边界，以及既有正常行为；随后逐入口完善业务能力。

不为每个默认方法生成镜像式测试；优先使用现有测试和必要的行为测试。代码接入时执行 `mvn -pl agent -am test`。

## 9. 本轮暂缓的业务细节

- afterTool 的加工范围、raw/effective 表示、投影存储和恢复方式尚未确定；本轮只确定入口，不承诺具体持久化步骤。
- 模型响应和工具参数的可修改范围、重试、路由、任意跳转暂不开放。
- 自动压缩不作为框架接入前提，也不强制经过旧 CONTEXT_ACTION 控制点。现有 ContextWindowPolicy 与 CompactionService 保留；自动协调、重建上限和失败处理另行设计。
- 压缩前必须重新评估未完成回合覆盖、摘要信任边界、输出预算和无进展循环等既有风险。
- 当前消息每轮从外部来源重读；未来数据加工必须明确作用于消费投影还是持久化事实，不能覆盖真实历史。

相关待办见 [待完善事项](pending-improvements.md)。实际接入后同步更新本文状态与 docs/diagrams/，不得把规划解释为已有行为。
