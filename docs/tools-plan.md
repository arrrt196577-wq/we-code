# we-code 五工具落地规划

> 目标工具集（Code Agent 向）：`read` / `glob` / `grep` / `edit` / `bash`  
> 原则：先契约与沙箱，再只读探索，后写入与执行；每个工具独立可测、可演示。

对应总规划：[mvp-1month-plan.md](./mvp-1month-plan.md)（原 `list_dir` / `write_file` / `run_shell` 由此五工具替代）。

---

## 1. 为什么这个顺序

| 顺序 | 工具 | 理由 |
|------|------|------|
| 0 | 契约 + Registry + 路径沙箱 | 五个工具共用；没有沙箱不能写 FS 工具 |
| 1 | `read` | 最简单、无副作用；验证沙箱/大小上限/返回格式 |
| 2 | `glob` | 解决「文件在哪」；只读，实现量小 |
| 3 | `grep` | 解决「代码在哪」；复用目录遍历与忽略规则 |
| 4 | `edit` | 首次写盘；精确替换，比整文件 write 更适合改码 |
| 5 | `bash` | 风险最高（超时、输出截断、权限）；放最后，且依赖前四验证改动 |

**不要先做 `bash`**：能跑命令不等于能稳改代码，且安全面最大。  
**不要先做 `edit`**：没有可靠的 `read`/`grep`，模型会瞎改。

---

## 2. Phase 0 — 工具基础设施（先于任何具体工具）

**交付：**

1. 升级 `Tool` SPI（建议形态）：
   - `name()` / `description()`
   - `parametersJson()` → 供 `ToolSpec` 暴露给 LLM
   - `execute(ToolContext ctx, String argumentsJson) → ToolResult`
2. 实现 `ToolContext`：至少含 `Path workspaceRoot`
3. 实现 `WorkspacePaths`（或同类工具类）：
   - 相对路径解析
   - 禁止 `..` 逃出 workspace
   - 统一错误文案（给模型看的 observation）
4. 实现 `ToolRegistry`：
   - `register` / `get` / `listSpecs` / `execute(toolCallId, name, argsJson)`
5. 约定：工具失败也返回 `ToolResult.failed(...)`，**不抛到 AgentLoop 打断整轮**（除非是注册表找不到工具名这类协议错误）

**Done when：** 能注册一个 `noop` 工具，`listSpecs` 非空，`execute` 返回 `ToolResult`。

**建议耗时：** 0.5～1 天

---

## 3. 五个工具：分步规格与验收

### Step 1 — `read`（先做）

**参数（建议）：**

| 字段 | 类型 | 说明 |
|------|------|------|
| `path` | string | workspace 相对路径 |
| `offset` | int? | 从第几行开始（1-based，可选） |
| `limit` | int? | 最多读多少行（可选） |

**行为：**

- 路径必须在 workspace 内
- 默认整文件；过大则截断或要求带 offset（建议硬上限如 200KB / 2000 行）
- 文本文件；二进制可拒绝并返回明确错误

**Done when：** 对 workspace 内文件读出内容；越界路径失败且不抛崩。

---

### Step 2 — `glob`

**参数：**

| 字段 | 类型 | 说明 |
|------|------|------|
| `pattern` | string | 如 `**/*.java` |
| `path` | string? | 搜索根，相对 workspace，默认 `.` |

**行为：**

- 只返回路径列表（可限制条数，如最多 200）
- 默认跳过 `target/`、`.git/`、`node_modules/`（后续再接 `.gitignore`）
- 路径全部相对 workspace

**Done when：** `**/*Test.java` 能列出期望文件；越界根目录失败。

---

### Step 3 — `grep`

**参数：**

| 字段 | 类型 | 说明 |
|------|------|------|
| `pattern` | string | 正则或字面（MVP 先正则，失败则当字面） |
| `path` | string? | 文件或目录，默认 `.` |
| `glob` | string? | 可选文件过滤，如 `*.java` |
| `case_insensitive` | bool? | 可选 |

**行为：**

- 输出：`path:line:content` 风格摘要
- 限制：匹配条数上限（如 100）、单行截断
- 同 glob 的忽略目录规则

**Done when：** 能在 demo 仓库里搜到类名/方法名；无匹配时返回明确「0 hits」而不是异常。

---

### Step 4 — `edit`

**参数：**

| 字段 | 类型 | 说明 |
|------|------|------|
| `path` | string | 目标文件 |
| `old_string` | string | 精确匹配的旧文本 |
| `new_string` | string | 替换后的新文本 |
| `replace_all` | bool? | 默认 false；false 时 old 必须唯一 |

**行为：**

- 文件不存在：允许「创建」——仅当 `old_string` 为空且写入 `new_string`（或单独约定）；MVP 可规定：不存在则失败，新建暂用后续扩展 / `bash`
- **推荐 MVP：**  
  - 已存在：精确替换（唯一匹配）  
  - 不存在：若 `old_string` 为空则创建新文件，否则失败  
- 替换 0 次或多于 1 次（且非 replace_all）：失败，提示模型先 `read`
- 写回使用 UTF-8；写前仍做路径沙箱

**Done when：** 能把某文件中一段唯一文本改掉；old 不唯一时失败可观测。

**说明：** 本月不做完整 `apply_patch` / 多文件事务；一个 call 改一个文件即可。

---

### Step 5 — `bash`

**参数：**

| 字段 | 类型 | 说明 |
|------|------|------|
| `command` | string | 要执行的命令 |
| `timeout_ms` | int? | 可选，默认 30000 |

**行为：**

- `cwd` 固定为 workspace root
- 超时杀进程；stdout/stderr 合并或分栏，总长度截断（如 32KB）
- 返回 exit code + 输出摘要
- Phase 0 可先无交互确认；接入 Permission 模块时再拦（见总规划 Week 3）

**Done when：** `mvn -q -DskipTests compile`（或 demo 内等价命令）能跑完并回传输出；超时能停。

---

## 4. 日程建议（约 3～5 天）

| 日 | 产出 | 验收口令 |
|----|------|----------|
| D1 上午 | Phase 0：SPI + Registry + WorkspacePaths | noop 可注册可执行 |
| D1 下午 | `read` | 读文件 + 越界失败 |
| D2 上午 | `glob` | 模式列文件 |
| D2 下午 | `grep` | 关键词命中带行号 |
| D3 | `edit` | 唯一替换成功 / 歧义失败 |
| D4 | `bash` | 带超时跑通一条构建命令 |
| D5（缓冲） | CLI 装配五工具 + Fake/真模型各打一轮 | 「搜 → 读 → 改 → 跑」人工脚本或 Agent 一步 |

与 AgentLoop 的衔接：**D1 起 Registry 可用后**，Loop 即可 `listSpecs` + `execute`；不必等五个全齐再接线——可每完成一个工具就进注册表。

---

## 5. 包结构建议

```
tools/src/main/java/org/wecode/tools/
├── spi/Tool.java
├── spi/ToolContext.java
├── result/ToolResult.java
├── registry/ToolRegistry.java
├── path/WorkspacePaths.java          # 沙箱
├── impl/
│   ├── ReadTool.java
│   ├── GlobTool.java
│   ├── GrepTool.java
│   ├── EditTool.java
│   └── BashTool.java
└── ignore/IgnoreRules.java           # 可选，D2+ 抽公共忽略
```

命名对外（给模型的 tool name）建议固定为：`read` / `glob` / `grep` / `edit` / `bash`。

---

## 6. 与权限 / Prompt 的边界

| 能力 | 何时做 | 说明 |
|------|--------|------|
| 路径沙箱 | Phase 0 / 每个工具 | 必须 |
| `edit`/`bash` 确认 | Week 3 Permission | 本规划先实现工具逻辑，预留 hook |
| system prompt | Agent 接线时 | 要求：先 grep/glob → read → edit → bash 验证 |
| `.gitignore` | grep/glob 稳定后 | 可先硬编码忽略目录 |

---

## 7. 刻意不做（避免膨胀）

- `list_dir`（由 `glob` 覆盖）
- 整文件 `write`（由 `edit` 创建/替换覆盖）
- LSP / web_search / todo / 多文件 patch
- Windows / Unix 命令白名单细粒度（可放 Week 4 亮点 B）

---

## 8. 下一步（立刻开工）

1. 改 `Tool` + 落地 `ToolContext` / `WorkspacePaths` / `ToolRegistry`
2. 实现并注册 `read`
3. 用临时 main 或单测：给定临时目录，读一个文件

完成 Step 1 后再开 `glob`，不要并行堆五个空实现。
