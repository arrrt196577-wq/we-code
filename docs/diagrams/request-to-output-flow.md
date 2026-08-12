# 用户请求到最终输出流程图

本图描述当前 CLI 一次性任务的实际执行流程，入口为 `org.wecode.cli.Main`，核心循环为 `org.wecode.agent.AgentLoop`。

```mermaid
flowchart TD
    USER(["用户通过 CLI 提交自然语言任务"])

    subgraph INIT["CLI 初始化与装配"]
        PARSE["Picocli 解析配置路径和 TASK 参数"]
        PROJECT["ProjectResolver 识别启动目录、项目根目录和项目类型"]
        CONFIG["加载 wecode.yml<br/>解析 Provider 与存储目录"]
        INFO["输出配置、项目和任务摘要"]
        MODEL["ChatModelFactory 创建 OpenAiChatModel"]
        REGISTRY["注册 Read、Glob、Grep、Edit 工具"]
        SESSION["创建内存 Session<br/>追加 system prompt 和 user 消息"]
        LOOP["创建 AgentLoop<br/>最大 LLM 轮次为 16"]
    end

    subgraph AGENT["Agent 循环"]
        STEP["输出当前 step 进度"]
        CHAT["ChatModel.chat<br/>发送完整消息历史和工具定义"]
        HTTP["调用 OpenAI 兼容<br/>/chat/completions 非流式接口"]
        RESPONSE["解析文本、thinking、finish reason 和 tool_calls"]
        APPEND_ASSISTANT["将 assistant 响应追加到内存 Session"]
        SAVE_CONTENT{"响应文本是否非空？"}
        REMEMBER["保存为 lastContent"]
        HAS_TOOLS{"是否包含 tool_calls？"}
    end

    subgraph TOOLS["工具调用处理"]
        NEXT_CALL["按返回顺序取下一条 tool call"]
        EXECUTE["ToolRegistry.execute<br/>按名称查找并执行工具"]
        RESULT["生成成功或失败的 ToolResult"]
        APPEND_TOOL["以 tool 消息追加执行结果"]
        MORE_CALLS{"本轮还有 tool call？"}
        MORE_STEPS{"当前 step 小于 16？"}
    end

    subgraph OUTPUT["结束与输出"]
        NORMAL_REPLY["AgentLoop 返回 lastContent<br/>空值按空字符串处理"]
        LIMIT_REPLY["返回 lastContent 加停止说明<br/>或无最终答案提示"]
        PRINT["Main 输出分隔线和最终文本"]
        SUCCESS(["进程以状态码 0 结束"])
        FAILED(["异常上抛，当前任务中断"])
    end

    USER --> PARSE
    PARSE --> PROJECT --> CONFIG --> INFO --> MODEL --> REGISTRY --> SESSION --> LOOP
    LOOP --> STEP --> CHAT --> HTTP --> RESPONSE --> APPEND_ASSISTANT --> SAVE_CONTENT

    CONFIG -. "配置读取或校验异常" .-> FAILED
    HTTP -. "网络、HTTP 或响应解析异常" .-> FAILED

    SAVE_CONTENT -->|"是"| REMEMBER --> HAS_TOOLS
    SAVE_CONTENT -->|"否"| HAS_TOOLS
    HAS_TOOLS -->|"否"| NORMAL_REPLY
    HAS_TOOLS -->|"是"| NEXT_CALL --> EXECUTE --> RESULT --> APPEND_TOOL --> MORE_CALLS
    MORE_CALLS -->|"是"| NEXT_CALL
    MORE_CALLS -->|"否"| MORE_STEPS
    MORE_STEPS -->|"是"| STEP
    MORE_STEPS -->|"否"| LIMIT_REPLY
    NORMAL_REPLY --> PRINT --> SUCCESS
    LIMIT_REPLY --> PRINT
```

## 当前实现边界

- `Session` 当前仅维护内存消息列表；`workspaces`、`sessions`、`session_message` 和 `tool_execution` 四张表尚未接入这条 CLI 调用链，因此进程结束后本次对话不会由该流程持久化。
- LLM 请求当前是非流式调用，收到完整 `LlmResponse` 后才继续执行。
- 同一轮中的多条工具调用按模型返回顺序串行执行；工具失败也会转换为 `tool` 消息回灌给模型，由模型决定下一步。
- 正常停止条件只有“本轮没有工具调用”；达到 16 轮后强制停止。`StopCondition` 类当前尚未接入。
- `permission` 模块当前没有在 `Main` 或 `AgentLoop` 中参与工具执行授权。

## 主要代码位置

- `cli/src/main/java/org/wecode/cli/Main.java`：CLI 参数解析、依赖装配和最终输出。
- `agent/src/main/java/org/wecode/agent/AgentLoop.java`：模型调用、工具执行和停止逻辑。
- `llm/src/main/java/org/wecode/llm/chat/OpenAiChatModel.java`：请求构造、HTTP 调用和响应解析。
- `tools/src/main/java/org/wecode/tools/registry/ToolRegistry.java`：工具注册、查找和执行。
- `session/src/main/java/org/wecode/session/Session.java`：当前的内存消息历史。
