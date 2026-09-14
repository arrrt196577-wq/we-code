# 项目图表

本目录用于集中管理帮助理解项目设计与运行机制的图表，包括：

- 系统与模块架构图
- 核心业务流程图
- 模块交互时序图
- 状态流转图
- 模块及外部依赖关系图

图表优先使用 Mermaid 编写。包含设计说明时使用 Markdown（`.md`）文件；仅包含 Mermaid 图表源码时可以使用 `.mmd` 文件。

当代码变更涉及模块边界、核心流程、状态流转或依赖关系时，应同步检查并更新本目录中的相关图表。

## 图表索引

- [Agent 横切扩展运行时（规划）](agent-extension-runtime.md)：展示每次运行独立的 Runtime、统一 Middleware 阶段分发、Lifecycle 及主循环接入骨架。
- [Hook 模块依赖边界](hook-module-dependencies.md)：展示 agent 内阶段扩展框架与保留现状的 hook 模块边界。
- [会话数据库实体关系图](session-database-er.md)：展示 `workspaces`、`sessions`、`session_message` 和 `tool_execution` 四张 baseline V1 表的字段、约束及关联关系。
- [WeCode 工作区启动流程图](request-to-output-flow.md)：展示 CLI 检查当前真实目录、确认并创建 Workspace 或取消退出的当前流程。
- [Memory 架构与写入流程（规划）](memory-architecture.md)：展示拟议上下文组成、自动提取事务边界、记忆生命周期与模块依赖，尚未实现。
