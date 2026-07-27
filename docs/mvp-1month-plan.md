# we-code 一个月 MVP 规划

> 目标：做出可演示、可写进简历的轻量 Code Agent，而不是复刻 OpenCode。  
> 原则：先闭环、再体验、后亮点；砍掉 IDE/桌面端/多 Provider 生态等重型能力。

## 1. 产品定位

**一句话**：基于 LLM Function Calling 的本地 Code Agent——在指定工作区完成读码、改码、跑命令，支持基础会话与危险操作确认。

**非目标（本月不做）**：

- Desktop / 完整 TUI / Web IDE
- 多模型生态、插件市场、MCP 全兼容
- LSP 全家桶、多 Agent 编排、Worktree / Snapshot
- 企业控制台、账号体系

## 2. 成功标准（月末验收）

同时满足以下条件即视为 MVP 完成：

1. CLI 可运行：`java -jar ... --workspace <dir> "<task>"`
2. Agent Loop 稳定：think → tool → observe，带最大步数限制
3. 至少 4 个工具可用：`list_dir` / `read_file` / `write_file` / `run_shell`
4. 对接至少一个 OpenAI 兼容 Provider（可用环境变量配置）
5. 流式或逐步输出工具调用过程（至少非黑盒）
6. 写文件 / 跑 shell 支持确认策略（可 `--yes` 跳过）
7. 至少 **2 个** demo 可复现跑通，README 有运行说明与架构图
8. 简历可写成「本地 Code Agent + 工具循环 + 权限/上下文」而不是「调用了 Chat API」

## 3. 模块与职责（沿用现有骨架）

```
we-code/
├── cli/           # 参数解析、依赖装配、进程入口
├── agent/         # loop、prompt、停止条件
├── tools/         # 工具契约、注册表、具体工具
├── llm/           # Provider 抽象与 OpenAI 兼容适配
├── session/       # 会话消息与（后期）持久化
├── permission/    # 确认 / 放行策略
├── demos/         # 固定可复现任务
└── docs/          # 规划与设计文档
```

| 模块 | MVP 最低交付 |
|------|----------------|
| `cli` | `--workspace`、任务文本、`--max-steps`、`--yes` |
| `agent` | 可运行循环 + system prompt + 步数停止 |
| `tools` | 4 工具 + Registry + 工作区路径约束 |
| `llm` | `chat(messages, tools)` + OpenAI 兼容实现；可选 Fake 供本地测 |
| `session` | 内存消息列表；月末可落盘 JSON/SQLite |
| `permission` | 写文件/shell 确认；`--yes` 全放行 |

## 4. 四周节奏总览

| 周次 | 主题 | 关键产出 | 验收口令 |
|------|------|----------|----------|
| Week 1 | 跑通内核 | Fake/真 LLM + 4 工具 + Agent Loop + CLI | 能改一个文件并打印总结 |
| Week 2 | 真任务闭环 | 真实 Provider + demo 靶场 + 路径沙箱/超时 | demo 02（NPE）跑通 |
| Week 3 | 产品体验 | 权限确认 + 过程输出 + 会话落盘 | 危险操作可拦截，过程可回放 |
| Week 4 | 简历打磨 | 第 2 个 demo + 深挖 1 点 + 文档/录屏 | 3 分钟现场 demo 可讲清 |

---

## 5. 分周详细计划

### Week 1 — Phase 1 前半：最小闭环

**目标**：不依赖「完美 Prompt」，先让循环转起来。

**任务**：

1. 定数据模型：`Message` / `ToolCall` / `LlmResponse` / `ToolResult`
2. 升级 `LlmProvider`：从 `complete(prompt)` 改为带 tools 的 `chat(...)`
3. 实现 `FakeLlmProvider`（脚本化 tool call），便于无 Key 调试
4. 实现工具：`list_dir`、`read_file`、`write_file`、`run_shell`
5. 实现 `ToolRegistry`：`register` / `get` / `listSpecs` / `execute`
6. 实现 `AgentLoop`：最大步数（建议 12–20）+ 基础 `PromptBuilder`
7. CLI 能解析 `--workspace` 与任务文本并装配模块

**Done when**：

```text
Fake 模式下：用户任务 → 若干 tool 调用 → 写出文件 → 输出最终回复 → 正常退出
```

**本周不做**：权限交互、流式 SSE、会话持久化、真实 demo 源码完善。

---

### Week 2 — Phase 1 后半：真实可用

**目标**：换成真模型，对着真实小仓库完成一次「修 Bug」。

**任务**：

1. 实现 `OpenAiProvider`（`WECODE_API_KEY` / `WECODE_BASE_URL` / `WECODE_MODEL`）
2. 工具加固：
   - 所有路径限制在 workspace 内
   - `read_file` 大小上限（如 200KB）
   - `run_shell` 超时（如 30s）与输出截断
3. 补齐 `demos/02-fix-null-npe` 靶场源码与验证方式
4. 调 system prompt：要求先读再改、改完尽量验证、路径用相对路径
5. 打包：`mvn package` 后 `java -jar` 一键运行
6. 更新根 `README.md` 运行说明

**Done when**：

```bash
java -jar cli/target/cli-*.jar \
  --workspace demos/02-fix-null-npe \
  "修复空指针问题"
```

Agent 能定位、修改，并（尽量）通过本地编译/测试验证。

**本周不做**：Web UI、多 Provider 切换 UI、复杂检索。

---

### Week 3 — Phase 2：像产品一点

**目标**：从「脚本能跑」到「敢给别人演示」。

**任务**：

1. **Permission**：写文件 / `run_shell` 前确认；支持 `--yes`
2. **过程可见**：逐步打印 assistant 文本、tool 名、入参摘要、结果摘要（完整 token 流式可选）
3. **Session 持久化**：至少 JSON 落盘（会话 id、消息历史），支持继续同一会话（最小实现即可）
4. 忽略规则：尊重 `.gitignore` 或硬编码排除 `target/`、`.git/`、`node_modules/`
5. 补齐 `demos/01-hello-refactor` 或强化 demo 02 的稳定性

**Done when**：

- 无 `--yes` 时，危险操作会停下来询问
- 一次运行的工具轨迹人眼可读
- 重启进程后能加载历史消息（哪怕很简陋）

---

### Week 4 — Phase 3：一个亮点 + 简历包装

**目标**：有差异化，而不是功能堆砌。**只深挖 1 个亮点。**

**三选一（建议优先 A 或 B）**：

| 选项 | 亮点 | 面试可讲点 |
|------|------|------------|
| A 上下文策略 | 简单按路径/关键词检索，控制进模上下文 | 如何避免上下文爆炸与成本失控 |
| B 安全沙箱 | 命令白名单、禁止改 `.env`、更严路径策略 | Agent 安全与权限模型 |
| C 可观测性 | 每步耗时、token/字符统计、失败原因分类 | 如何调试与评估 Agent |

**同时完成**：

1. 第二个可复现 demo（`01` 或 `03-add-unit-test`）
2. `docs/` 补充简短架构说明（可链到本规划）
3. 终端录屏或 asciinema / GIF（30–90 秒）
4. 简历项目描述草稿（放在 `docs/resume-blurb.md` 可选）

**Done when**：能在 3 分钟内现场演示「提任务 → 工具轨迹 → 改代码 → 验证」，并讲清 Loop / Tools / Permission（+ 所选亮点）。

---

## 6. 里程碑检查清单

### M1（约第 7 天）— 内核可转

- [ ] `chat` + tools 协议打通（Fake 即可）
- [ ] 4 个工具可执行
- [ ] AgentLoop 达最大步数会停止
- [ ] CLI 入口不再抛 `UnsupportedOperationException`

### M2（约第 14 天）— 真模型闭环

- [ ] OpenAI 兼容 Provider 可用
- [ ] workspace 路径沙箱生效
- [ ] demo 02 至少成功 1 次端到端
- [ ] README 可按文档复现

### M3（约第 21 天）— 可演示

- [ ] 权限确认或 `--yes`
- [ ] 工具调用过程可见
- [ ] 会话可保存/加载（最小版）
- [ ] 排除构建产物等噪音目录

### M4（约第 30 天）— MVP 交付

- [ ] 2 个 demo 稳定可复现
- [ ] 1 个深挖亮点落地
- [ ] 架构说明 + 演示素材
- [ ] 简历表述定稿

## 7. 风险与应对

| 风险 | 影响 | 应对 |
|------|------|------|
| 模型不稳定 / 乱调工具 | demo 偶发失败 | 收紧 prompt；限制工具数量；固定 demo 仓库；可录一次成功轨迹 |
| API 费用或限流 | 调试中断 | Week 1 坚持 Fake；真调用加步数上限与日志 |
| 范围膨胀（想做 TUI/插件） | 月末无完整闭环 | 严格按「本月非目标」砍需求 |
| Shell 安全问题 | 误删/越权 | 强制 workspace；Week 3 上确认；Week 4 可选白名单 |
| Java 调 LLM JSON 繁琐 | 进度慢 | 先手写最小 DTO，避免过早引入重型框架 |

## 8. 简历写法参考（MVP 完成后）

> **WeCode**｜本地 Code Agent（个人项目）  
> - 设计并实现基于 Function Calling 的多步 Agent Loop，支持读文件、改代码、执行构建命令  
> - 实现危险操作权限确认与工作区路径约束，避免 Agent 越权改写  
> - （亮点句：上下文裁剪 / 安全沙箱 / 可观测性，三选一）  
> - 技术栈：Java 21、Maven 多模块；演示与源码：……

## 9. 相关文档

- 仓库说明：[../README.md](../README.md)
- 演示任务：[../demos/](../demos/)
- 参考对象：OpenCode（只学分层与 Agent 内核，不整仓模仿）

---

## 10. 下一步（立刻可做）

从 **Week 1 / Day 1** 开始：

1. 在 `llm` 模块定义 `Message`、`ToolCall`、`LlmResponse`
2. 改 `LlmProvider` 接口为 `chat(messages, tools)`
3. 落地 `FakeLlmProvider`，用固定脚本驱动 `write_file`
4. 让 `AgentLoop` 空转一圈并正常退出

完成以上四步后，再接入真实工具与真实模型。
