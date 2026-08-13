# Agent Notes

- This project is an OpenCode-style Code Agent
- OpenCode source code is located at `F:\opencode`
- Do not generate mock test code unless necessary
- When generating code, add appropriate Chinese comments: explain the purpose of methods and parameters; add step comments inside complex methods; also comment control-flow statements (such as `if`/`else`, `switch`, `for`/`while`, `try`/`catch`, `return`/`break`/`continue`, etc.)
- `docs/diagrams/` is the central directory for project diagrams, including Mermaid architecture, flow, sequence, state, and dependency diagrams. When changes affect module boundaries, core workflows, state transitions, or dependencies, update the corresponding diagram documentation in this directory.
- `docs/pending-improvements.md` 用于记录当前明确暂缓、但后续必须重新评估和完善的设计与实现事项；相关功能重新启用或改动时，应同步更新该文档。
