# WeCode 工作区启动流程图

当前 `wecode` 命令只检查当前启动目录是否已初始化为工作区；不接收任务参数，也不启动 Agent。

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
    SUCCESS([进程以 0 退出])
    EXIT([进程以 1 退出])
    FAILED([上抛异常并退出])

    START --> PARSE --> CONFIG --> DATABASE --> RESOLVE --> EXISTS
    EXISTS -->|是| READY --> SUCCESS
    EXISTS -->|否| PROMPT --> ANSWER
    ANSWER -->|是| CREATE --> CREATED --> SUCCESS
    ANSWER -->|否、空输入、EOF 或无效输入| CANCEL --> WAIT --> EXIT
    CREATE -.数据库写入异常.-> RACE
    RACE -->|是| RACE_READY --> SUCCESS
    RACE -->|否| FAILED
```

## 当前边界

- 工作区路径由 `WorkspaceResolver` 解析为当前启动目录的真实路径；查询与创建始终使用同一文本。
- 创建记录固定为 `LOCAL_DIRECTORY`；Git 类型识别暂不影响持久化类型。
- 用户输入仅接受 `Y`/`yes`（忽略大小写与首尾空白）；`N`/`no`、其他输入、空输入和 EOF 都不会创建工作区。
- 用户取消后由 `WorkspaceStartupService` 输出退出提示、等待 5 秒，再由 `Main` 以退出码 `1` 结束；测试注入等待器，不进行真实等待。
