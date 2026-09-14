# Memory 架构与写入流程（规划）

状态：待实现设计，不能视为当前运行流程。详见 [Memory 架构规划](../memory-architecture.md)。现有 session/compaction 流程继续由原图描述。

## 上下文组成

```mermaid
flowchart TD
    Scope[宿主解析 profile / workspace / repo / task] --> Filter[命名空间硬过滤和适用条件校验]
    Store[(Memory 条目与修订)] --> Filter
    Filter --> Select[有效版本选择与预算裁剪]
    History[(原始 session 历史与 COMPACTION)] --> Projection[会话历史投影]
    Files[授权范围内 AGENTS.md 与当前文件] --> Context[指令加载与事实校验]
    Projection --> Assemble[AgentContextAssembler]
    Context --> Assemble
    Select --> Assemble
    Assemble --> Budget[ContextWindowPolicy 统计完整请求]
    Budget --> LLM[主任务 ChatModel]
    LLM --> Tools[已有权限边界内执行工具]
    Tools --> History
    LLM --> History
```

Memory 动态注入结果不回写 session_message，不作为 Compaction 或长期提取的原始证据。图中主模型写回仅指其实际 assistant 响应，工具写回仅指执行状态和结果。

## 自动提取的事务边界（第 2 期）

```mermaid
sequenceDiagram
    participant CLI as SessionInteractionHandler
    participant DB as 同一 SQLite 数据库
    participant Worker as MemoryCoordinator
    participant Extractor as LlmMemoryExtractor
    participant Policy as 宿主校验与冲突策略
    CLI->>DB: 一个事务提交原始 USER / 终止事件与 memory_job
    DB-->>CLI: 提交成功
    CLI->>CLI: 继续主任务；显式记忆可短时等待结果
    Worker->>DB: 短事务领取 job，生成 lease token
    DB-->>Worker: 冻结的源区间、scope 和 generation
    Worker->>DB: 读取允许的原始证据
    Worker->>Extractor: 事务外、无工具、有界模型请求
    Extractor-->>Worker: 结构化候选
    Worker->>Policy: 校验引用、作用域、意图和冲突
    Policy-->>Worker: ACTIVE / CANDIDATE / 拒绝 / 无候选
    Worker->>DB: 校验 lease + generation + entry version
    alt 校验成功
        DB->>DB: 原子写入候选或修订，完成 job 并推进游标
        DB-->>Worker: 已提交
    else 撤销、旧租约或并发修订
        DB-->>Worker: 拒绝迟到提交；重新评估或标为过期
    end
```

## 记忆生命周期

```mermaid
stateDiagram-v2
    [*] --> CANDIDATE: 语义提取
    [*] --> ACTIVE: 显式写入且校验成功
    CANDIDATE --> ACTIVE: 用户确认
    CANDIDATE --> REJECTED: 用户拒绝或证据不足
    ACTIVE --> SUPERSEDED: 同范围明确纠正并创建新版本
    ACTIVE --> EXPIRED: 有效期或适用生命周期结束
    ACTIVE --> REVOKED: 用户忘记或撤销
    CANDIDATE --> REVOKED: 用户撤销来源
    REJECTED --> [*]
    SUPERSEDED --> [*]
    EXPIRED --> [*]
    REVOKED --> [*]
```

REVOKED 不因旧任务重试自动恢复；重新记住必须是新的显式操作。历史版本保留不代表可进入当前检索。

## 初期模块依赖

```mermaid
flowchart LR
    CLI[cli：装配与 worker] --> Agent[agent：上下文组装]
    CLI --> Session[session：已有存储及 Memory SQL 适配器]
    CLI --> Memory[memory：领域、服务、提取和 SPI]
    Agent --> Session
    Agent --> Memory
    Session --> Memory
    Memory --> LLM[llm]
    Memory --> ID[id]
    Session --> DB[(既有 SQLite / Flyway / MyBatis)]
```

此图只展示与 Memory 相关的拟议依赖，不替代全项目依赖图。memory 不反向依赖 session；SQL 适配器实现 memory SPI，来源通过证据端口注入。
