# WeCode 工作区启动流程图

当前 `wecode` 命令先检查当前启动目录是否已初始化为工作区；成功后进入长期交互循环。首条自然语言会创建并绑定一个 session，后续自然语言持续追加至该 session；`/resume` 暂未实现。

```mermaid
flowchart TD
    CONFIG_SOURCE[优先加载外部 wecode.yml；文件不存在时读取 Jar 内置 wecode.yml]
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
    COMPACT[/compact/]
    ACTIVE{是否已有活动 session}
    CREATE_SESSION[原子写入 session、SYSTEM、首条 USER 和临时标题]
    TITLE[仅根据首条 USER 无工具调用标题模型一次]
    TITLE_OK{标题是否合法且仍为临时标题}
    UPDATE_TITLE[更新为 MODEL 标题]
    APPEND_USER[追加 USER 消息]
    LOAD_CONTEXT[每轮请求前从 SQLite 组装 LLM messages]
    CONTEXT[按已组装 messages 和工具定义估算窗口；达到阈值仅标记压缩需求]
    AGENT[运行 Agent；同步持久化 assistant、工具领取和结果]
    RENAME_OK[更新标题并标记 USER 来源]
    UNKNOWN[输出不支持的命令]
    SUCCESS([进程以 0 退出])
    EXIT([进程以 1 退出])
    FAILED([上抛异常并退出])

    subgraph COMPACTION_MANUAL[第 1 期-A/B：手动 compaction 闭环]
        COMPACT_ALLOWED{无参数且有活动 session}
        COMPACT_REJECT[输出命令参数或活动会话错误]
        LOAD_SNAPSHOT[CompactionService 读取稳定快照]
        COMPACTABLE{是否有未覆盖原始历史}
        COMPACT_NONE[输出无新增内容]
        SUMMARY_CHAT[调用摘要模型：无 tools、max output 2048]
        SUMMARY_VALIDATE[拒绝 tool calls、LENGTH、空摘要和不完整 Markdown]
        CHECKPOINT_CAS[以快照版本和序号 CAS 追加 COMPACTION]
        COMMIT_RESULT{条件提交成功}
        COMPACT_OK[输出会话已压缩]
        COMPACT_STALE[输出快照过期，请重试]
    end

    START --> PARSE --> CONFIG --> CONFIG_SOURCE --> DATABASE --> RESOLVE --> EXISTS
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
    COMMAND -->|/compact| COMPACT --> COMPACT_ALLOWED
    COMMAND -->|未知 /命令| UNKNOWN --> INTERACTION_INPUT
    COMMAND -->|自然语言| ACTIVE
    ACTIVE -->|否| CREATE_SESSION --> TITLE --> TITLE_OK
    TITLE_OK -->|是| UPDATE_TITLE --> LOAD_CONTEXT --> CONTEXT --> AGENT
    TITLE_OK -->|否| LOAD_CONTEXT --> CONTEXT --> AGENT
    ACTIVE -->|是| APPEND_USER --> LOAD_CONTEXT
    RENAME -->|有活动 session 且参数合法| RENAME_OK --> INTERACTION_INPUT
    RENAME -->|否则| INTERACTION_INPUT
    AGENT --> INTERACTION_INPUT
    COMPACT_ALLOWED -->|否| COMPACT_REJECT --> INTERACTION_INPUT
    COMPACT_ALLOWED -->|是| LOAD_SNAPSHOT --> COMPACTABLE
    COMPACTABLE -->|否| COMPACT_NONE --> INTERACTION_INPUT
    COMPACTABLE -->|是| SUMMARY_CHAT --> SUMMARY_VALIDATE --> CHECKPOINT_CAS --> COMMIT_RESULT
    COMMIT_RESULT -->|是| COMPACT_OK --> INTERACTION_INPUT
    COMMIT_RESULT -->|否，快照过期| COMPACT_STALE --> INTERACTION_INPUT
```

## 当前边界

- 工作区路径由 `WorkspaceResolver` 解析为当前启动目录的真实路径；查询与创建始终使用同一文本。
- 创建记录固定为 `LOCAL_DIRECTORY`；Git 类型识别暂不影响持久化类型。
- 用户输入仅接受 `Y`/`yes`（忽略大小写与首尾空白）；`N`/`no`、其他输入、空输入和 EOF 都不会创建工作区。
- 用户取消后由 `WorkspaceStartupService` 输出退出提示、等待 5 秒，再由 `Main` 以退出码 `1` 结束；测试注入等待器，不进行真实等待。
- 工作区确认成功后，`Main` 将同一个 `BufferedReader` 交给交互循环，避免不同读取器竞争 `System.in`。`/exit`（忽略首尾空白）或 EOF 时以退出码 `0` 结束。
- 首条非命令自然语言会创建 session，并在同一事务中写入固定 system prompt、首条 USER 和从首条消息截断的临时标题。标题模型仅在此处调用一次；失败、超时或非法结果时保留临时标题，后续消息、程序重启和未来 `/resume` 都不会自动重试。
- `/rename <title>` 仅修改当前活动 session，标题标记为 `USER`；模型标题只可替换 `TEMPORARY` 标题，不能覆盖手动重命名。未知斜杠命令不会被当作自然语言，因此不会意外创建 session。
- 后续自然语言只向当前活动 session 追加 USER 消息。`activeSessionId` 仅保存在进程内作为选中标识；LLM 历史不再从内存 `Session` 读取。
- 每次模型请求前，Agent 从 SQLite 组装有序 `messages`：始终使用会话创建时持久化的首条 SYSTEM；存在 compaction 时紧随其后以带固定安全前缀的 ASSISTANT 历史消息注入最新摘要，并按 `sequence_no ASC` 回放其覆盖边界后的非压缩尾部。ASSISTANT 的 `tool_calls` 与紧随其后的终态 TOOL observation 一并恢复；未完成工具调用会拒绝构造请求上下文。
- 每次模型请求前按已组装消息与工具定义的 Unicode 字符数除以四估算输入 token。当前统一按 300k 上下文窗口、20k 安全余量和零输出 token 占位预算计算压缩阈值；达到阈值仅标记需求，尚不生成新的压缩摘要。
- `/compact` 是精确匹配且不接收参数的本地控制命令，不会作为 USER 消息或 Agent 轮次写入会话历史。它仅对当前活动 session 调用不感知触发来源的 `CompactionService`。
- `CompactionService` 在短暂读取中取得包含会话版本和最后序号的稳定快照，在数据库事务外生成摘要并校验固定 Markdown，然后以 CAS 追加 `COMPACTION`。若历史在生成期间变化，只返回过期快照而不写入旧摘要；没有新增历史时不调用模型。
- 第 1 期仍采用 `keepRecentTokens = 0`：一次手动压缩覆盖当前全部可压缩历史。自动压缩、原文尾部保留和 Provider overflow 恢复尚未接入。
