# we-code

轻量级 coding agent 框架（骨架阶段，业务逻辑待实现）。

## 模块结构

```
we-code/
├── cli/           # 入口：参数解析、组装依赖、启动会话
├── agent/         # loop、prompt、停止条件
├── tools/         # 每个工具一个类/文件
├── llm/           # provider 适配
├── session/       # 历史与状态
├── permission/    # 确认策略
├── demos/         # 固定可复现演示任务
└── README.md
```

## 架构

```mermaid
flowchart LR
    User -->|命令/任务| CLI
    CLI --> Agent
    Agent -->|拼装上下文| Prompt
    Agent -->|调用| LLM
    Agent -->|执行| Tools
    Agent -->|读写| Session
    Tools -->|确认| Permission
    LLM -->|provider| ProviderAdapters
```

调用关系概览：

| 模块 | 职责 | 主要依赖 |
|------|------|----------|
| `cli` | 进程入口与装配 | agent / session / permission / llm / tools |
| `agent` | 思考-执行循环、提示词、停止条件 | llm / tools / session / permission |
| `tools` | 工具契约与注册 | permission |
| `llm` | LLM Provider 抽象与适配 | — |
| `session` | 会话历史与持久化 | — |
| `permission` | 工具确认与授权策略 | — |

## 演示

见 [`demos/`](./demos/)：

1. [`01-hello-refactor`](./demos/01-hello-refactor/) — 小范围重构
2. [`02-fix-null-npe`](./demos/02-fix-null-npe/) — 修复空指针
3. [`03-add-unit-test`](./demos/03-add-unit-test/) — 补单元测试

> 演示 GIF / asciinema 录屏后续补充。

## 配置（YAML，无 Spring）

复制模板并按需修改（`wecode.yml` 已加入 `.gitignore`）：

```bash
copy wecode.yml.example wecode.yml
```

```yaml
llm:
  base-url: https://api.deepseek.com
  model: deepseek-chat
  temperature: 0.2
  reasoning-effort: medium
  return-thinking: true
  send-thinking: false
  thinking-field-name: reasoning_content
```

这些键由 `LlmConfig` 的 `@JsonProperty` 映射；改 `wecode.yml` 即可，无需改 Java。
API Key 请用环境变量（不要写进已提交的文件）：

```bat
set WECODE_API_KEY=sk-xxx
```

优先级：命令行 > 环境变量 > `wecode.yml` > 代码默认值。  
常用环境变量：`WECODE_API_KEY` / `WECODE_BASE_URL` / `WECODE_MODEL` / `WECODE_TEMPERATURE` / `WECODE_REASONING_EFFORT` / `WECODE_RETURN_THINKING` / `WECODE_SEND_THINKING` / `WECODE_THINKING_FIELD_NAME`。

实现位置：`cli` 模块的 `YamlConfigLoader` + `ConfigResolver`（Jackson YAML）。

## 构建

```bash
mvn -q -DskipTests package
```

先 `mvn install -DskipTests`，再运行（当前会打印已加载的 LLM 配置）：

```bash
mvn -q -pl cli exec:java
```

或在 IDE 中运行 `org.wecode.cli.Main`（工作目录设为仓库根目录，以便读到 `wecode.yml`）。
