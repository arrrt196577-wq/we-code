# we-code

轻量级 Coding Agent 框架，当前处于开发阶段。

## 模块结构

```text
we-code/
├── cli/           # 命令行入口、配置解析和依赖组装
├── agent/         # Agent 循环、提示词和停止条件
├── tools/         # 工具契约与实现
├── llm/           # LLM 协议适配器
├── session/       # 会话历史与状态
├── permission/    # 工具确认策略
└── demos/         # 可复现演示任务
```

## LLM 配置

复制模板后创建本地配置。`wecode.yml` 已被 Git 忽略，不应提交 API Key。

```bat
copy wecode.yml.example wecode.yml
```

```yaml
llm:
  active-provider: deepseek

  providers:
    deepseek:
      protocol: openai-chat-completions
      base-url: https://api.deepseek.com
      # 本地 wecode.yml 已被 .gitignore 忽略，可直接填写 API Key。
      api-key: ""
      model: deepseek-v4-flash
      thinking:
        enabled: true
        reasoning-effort: high
        response-field: reasoning_content
        send-toggle: true

    openai:
      protocol: openai-chat-completions
      base-url: https://api.openai.com/v1
      api-key: ""
      model: gpt-5.4
```

`active-provider` 是配置档案名；`protocol` 才决定使用的 Java 协议适配器。当前支持：

- `openai-chat-completions`：兼容 OpenAI Chat Completions 的服务，包括 DeepSeek 和内部网关。

因此，新增一个已兼容该协议的 Provider 只需要在 `providers` 下增加一个 YAML 节点，然后修改 `active-provider`；只有接入新的 API 协议时才需要增加 Java 适配器。

每个 Provider 可以使用 `api-key` 直接配置密钥，也可使用 `api-key-env` 指定环境变量。两者同时存在时，`api-key` 优先。由于直接配置密钥有误提交或被备份工具同步的风险，仅应写入已被 Git 忽略的本地 `wecode.yml`，绝不能写入 `wecode.yml.example`。

### 配置升级说明

配置格式已升级为多 Provider 档案。旧的 `llm.base-url`、`llm.api-key`、`llm.model` 及 `reasoning-*` 等平铺字段不再支持。开发阶段采用破坏性升级，不提供运行时兼容或自动迁移。

DeepSeek 的思考模式与工具调用同时开启时，会自动保存并回传 `reasoning_content`，以保证后续工具轮次可继续执行。思考模式下不发送 `temperature`。

## 构建

```bash
mvn -q test
```

安装当前多模块工程后运行 CLI：

```bash
mvn -q install
mvn -q -pl cli exec:java '-Dexec.args=你的任务'
```
