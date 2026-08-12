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

- [会话数据库实体关系图](session-database-er.md)：展示 `workspaces`、`sessions`、`session_message` 和 `tool_execution` 四张 baseline V1 表的字段、约束及关联关系。
- [用户请求到最终输出流程图](request-to-output-flow.md)：展示 CLI 接收任务、调用模型、执行工具循环并输出最终文本的当前流程。
