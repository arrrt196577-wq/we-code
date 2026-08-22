# WeCode 工作区启动流程图

当前 `wecode` 命令先检查当前启动目录是否已初始化为工作区；成功后进入长期交互循环。首条自然语言会创建并绑定一个 session，后续自然语言持续追加至该 session；`/resume` 暂未实现。

```mermaid
flowchart TD
    START([用户执行 wecode])
    PARSE[Picocli 解析帮助和可选配置路径]
    CONFIG[加载 wecode.yml 并解析 storage.root]
    DATABASE[打开 SessionDatabase]
    RESOLVE[WorkspaceResolver 解析当前真实目录]
    EXISTS{WorkspaceStore 按真实路径查询是否存在}
    READY[输出当前目录已是 WeCode 工作区]
    PROMPT[提示是否创建当前目录为工作区 Y/N]
    ANSWER{输入是 Y 或 yes 吗}
    CREATE[WorkspaceStore.create 写入 LOCAL_DIRECTORY]
    CREATED[输出工作区已创建]
    RACE{创建异常后路径是否已存在}
    RACE_READY[输出已由其他进程创建]
    CANCEL[输出已退出 WeCode]
    WAIT[等待 5 秒]
    INTERACTIVE[进入交互输入循环]
    INTERACTION_INPUT[读取一行终端输入]
    EXIT_CHECK{输入是否为 /exit 或 EOF}
    COMMAND{是否为本地命令}
    RENAME[/rename 标题/]
    RESUME[/resume 暂未实现/]
    ACTIVE{是否已有活动 session}
    CREATE_SESSION[原子写入 session、SYSTEM、首条 USER 和临时标题]
    TITLE[仅根据首条 USER 无工具调用标题模型一次]
    TITLE_OK{标题是否合法且仍为临时标题}
    UPDATE_TITLE[更新为 MODEL 标题]
    APPEND_USER[追加 USER 消息]
    CONTEXT[每轮请求前按字符÷4估算窗口；达到阈值仅标记压缩需求]
    AGENT[运行 Agent；同步持久化 assistant、工具领取和结果]
    RENAME_OK[更新标题并标记 USER 来源]
    UNKNOWN[输出不支持的命令]
    SUCCESS([进程以 0 退出])
    EXIT([进程以 1 退出])
    FAILED([上抛异常并退出])

    START --> PARSE --> CONFIG --> DATABASE --> RESOLVE --> EXISTS
    EXISTS -->|是| READY --> INTERACTIVE
    EXISTS -->|否| PROMPT --> ANSWER
    ANSWER -->|是| CREATE --> CREATED --> INTERACTIVE
    ANSWER -->|否、空输入、EOF 或无效输入| CANCEL --> WAIT --> EXIT
    CREATE -.数据库写入异常.-> RACE
    RACE -->|是| RACE_READY --> INTERACTIVE
    RACE -->|否| FAILED
    INTERACTIVE --> INTERACTION_INPUT --> EXIT_CHECK
    EXIT_CHECK -->|是| SUCCESS
    EXIT_CHECK -->|否| COMMAND
    COMMAND -->|/rename| RENAME
    COMMAND -->|/resume| RESUME --> INTERACTION_INPUT
    COMMAND -->|未知 /命令| UNKNOWN --> INTERACTION_INPUT
    COMMAND -->|自然语言| ACTIVE
    ACTIVE -->|否| CREATE_SESSION --> TITLE --> TITLE_OK
    TITLE_OK -->|是| UPDATE_TITLE --> CONTEXT --> AGENT
    TITLE_OK -->|否| CONTEXT --> AGENT
    ACTIVE -->|是| APPEND_USER --> CONTEXT
    RENAME -->|有活动 session 且参数合法| RENAME_OK --> INTERACTION_INPUT
    RENAME -->|否则| INTERACTION_INPUT
    AGENT --> INTERACTION_INPUT
```

## 当前边界

- 工作区路径由 `WorkspaceResolver` 解析为当前启动目录的真实路径；查询与创建始终使用同一文本。
- 创建记录固定为 `LOCAL_DIRECTORY`；Git 类型识别暂不影响持久化类型。
- 用户输入仅接受 `Y`/`yes`（忽略大小写与首尾空白）；`N`/`no`、其他输入、空输入和 EOF 都不会创建工作区。
- 用户取消后由 `WorkspaceStartupService` 输出退出提示、等待 5 秒，再由 `Main` 以退出码 `1` 结束；测试注入等待器，不进行真实等待。
- 工作区确认成功后，`Main` 将同一个 `BufferedReader` 交给交互循环，避免不同读取器竞争 `System.in`。`/exit`（忽略首尾空白）或 EOF 时以退出码 `0` 结束。
- 首条非命令自然语言会创建 session，并在同一事务中写入固定 system prompt、首条 USER 和从首条消息截断的临时标题。标题模型仅在此处调用一次；失败、超时或非法结果时保留临时标题，后续消息、程序重启和未来 `/resume` 都不会自动重试。
- `/rename <title>` 仅修改当前活动 session，标题标记为 `USER`；模型标题只可替换 `TEMPORARY` 标题，不能覆盖手动重命名。未知斜杠命令不会被当作自然语言，因此不会意外创建 session。
- 后续自然语言只向内存中的活动 session 追加 USER 消息并执行 Agent。assistant 响应、工具领取和工具结果均同步写入数据库；本阶段不支持切换或恢复 session。
- 每次模型请求前按完整消息与工具定义的 Unicode 字符数除以四估算输入 token。当前统一按 300k 上下文窗口、20k 安全余量和零输出 token 占位预算计算压缩阈值；达到阈值仅标记需求，尚不执行压缩。
