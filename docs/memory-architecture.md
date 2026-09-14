# WeCode Memory 架构规划

状态：设计提案，尚未实现。依据：2026-09-09 当前代码，以及《Agent Memory 和 Checkpoint》最新一轮完整问答。本文中的新增类、表、命令和配置均为拟议设计，不代表当前能力。

## 1. 结论与对原方案的修正

建议采用“会话执行状态 + 显式指令 + 当前工作副本事实 + 有作用域的长期记忆”，由统一上下文组装器在请求前组合。先完成 WORKSPACE/USER 的可控闭环，再开放 REPOSITORY 共享和自动提取。

原方案的三处关键修正：

1. **Scope、内容类型和生命周期是不同维度。** EPISODE 是历史经验类型，可以属于某个仓库；它不是比 BRANCH 更深的一层。Workspace、branch、path 也不是一棵严格包含树。用“命名空间 + 适用条件”表达，不能以任意一列匹配的 OR 查询替代完整隔离。
2. **当前事实与未来意图不能互相覆盖。** `pom.xml` 声明 Java 17，与“以后升级到 Java 21”可以同时成立。前者是当前观测，后者是目标决策；不能执行简单的“Repo Truth wins”而丢弃用户意图，也不能把目标记成升级已完成。
3. **后台提取是一条可恢复的写入流程。** 单纯在回调中启动 Future 会在 CLI 退出时丢任务；摘要、模型自述、步数耗尽也不能成为成功证据。必须有原始来源、持久化任务、幂等与过期写保护。

本规划按本机单用户 CLI、单个 session 单写入者、现有 SQLite/Flyway/MyBatis 设计。不引入云同步、向量数据库或知识图谱；这些都不是实现可靠 Memory 的前提。

## 2. 当前代码与设计接点

| 当前实现 | 已具备能力 | 对 Memory 的影响 |
|---|---|---|
| `cli/project/WorkspaceResolver` | 启动目录转真实路径；Git 只检测是否在仓库内 | cwd 不等于 repo root；缺 repository/worktree/branch 身份，不能直接共享仓库记忆 |
| `session/persistence/WorkspaceStore` | UUIDv7 workspace ID；按原样路径查询；新记录固定 LOCAL_DIRECTORY | 复用 workspace ID；统一路径身份前须处理别名，不能依赖 type 判断 Git |
| V1 migration | workspace 根路径和 session 工作区关联不可变 | 识别 repo root 不得扩大 `ToolContext` 的文件访问边界 |
| `SessionConversationStore` | 原始消息、工具状态、版本/序号 CAS、compaction 投影 | 可作为证据源；Memory 应读原始事件，不从 LLM 投影中再次提取 |
| `SessionInteractionHandler` | USER 先落库；监听器同步记录 assistant/工具结果 | USER 提交和回合结束是入队边界，不依赖未来才会有的 EventBus |
| `AgentLoop` | 每次模型请求重新读取消息，再计算上下文预算 | 在 `ConversationMessageProvider` 外围接入动态上下文组装 |
| `AgentLoop` + handler | 正常返回 String，步数耗尽同样返回 String；随后统一 markRunIdle | IDLE 是可交互状态，不是任务成功；经验提取前补结束原因 |
| `PromptBuilder` | 仅固定工具/行为提示 | 尚未自动加载 AGENTS.md；应先补显式指令加载 |
| `ChatModel` / `ChatRequestOptions` | 独立模型对象、无工具请求、maxOutputTokens | 可复用调用接口；尚无 JSON Schema 能力声明，默认三参实现还可能忽略选项 |
| `Main` | 主对话、标题、压缩共用模型；注册 Read/Glob/Grep/Edit | 当前没有 shell/test/commit 工具事件，不规划成“现有测试通过后自动提取” |

当前 session 历史持久化与 COMPACTION 是恢复基础，不等于任意程序位置的完整 checkpoint。`/resume` 尚未实现，未知工具副作用也不能靠 Memory 自动重放。已有 compaction 的未回复 USER 覆盖、摘要信任边界问题，见 [待完善事项](pending-improvements.md)。

关键源码：

- [WorkspaceResolver](../cli/src/main/java/org/wecode/cli/project/WorkspaceResolver.java)
- [SessionInteractionHandler](../cli/src/main/java/org/wecode/cli/interaction/SessionInteractionHandler.java)
- [SessionConversationStore](../session/src/main/java/org/wecode/session/persistence/SessionConversationStore.java)
- [AgentLoop](../agent/src/main/java/org/wecode/agent/AgentLoop.java)
- [PromptBuilder](../agent/src/main/java/org/wecode/agent/PromptBuilder.java)
- [ChatRequestOptions](../llm/src/main/java/org/wecode/llm/chat/ChatRequestOptions.java)

## 3. 四类上下文的职责

| 类别 | 内容 | 来源与更新 | 存储/有效期 |
|---|---|---|---|
| Session / Execution State | 用户请求、工具调用及结果、执行结束原因、未完成事项 | 原始事件确定性保存；摘要表达需要 LLM | 现有 session 表；按会话恢复 |
| Instruction Context | AGENTS.md、显式配置中的项目规则 | 文件加载与适用目录解析；不做语义提取 | 原文件是权威载体，缓存按内容哈希失效 |
| Workspace Facts | 当前文件声明、Git HEAD、可验证的环境观测 | 有边界的文件读取/解析、Git 探测 | 可重建缓存；携带来源、采集时间及版本 |
| Durable Memory | 跨会话偏好、决策理由、外部约束、参考入口、历史经验 | 用户显式写入或 LLM 提出候选；Java 决定生效 | 独立 memory 表，带来源、版本、状态 |

“Java 21”示例：根 POM 已声明 source/target=21，可直接读取；“今后本项目禁止降到 Java 17，因为部署环境要求 21”则是决策及理由，值得保存。不要把它们合成没有语义标签的 `java_version=21`。

文件事实也不是所有场景都能由静态解析完全确定。Maven 父 POM/profile、Gradle 脚本、环境覆盖可能改变实际值；初期记录 `DECLARED_VALUE` 和出处，无法解析就标记未知。不能为扫描事实擅自执行仓库构建脚本，也不应批量摄取 application.yml 中的密钥。

## 4. 作用域与身份隔离

### 4.1 Scope 与 Kind 分开

| Scope | 归属 | 可保存内容 | 首次开放 |
|---|---|---|---|
| USER | 本地稳定 profile ID | 明确跨项目的偏好；技术偏好必须附语言/适用条件 | 第 1 期，显式写入 |
| WORKSPACE | profile + 已有 workspace ID | 当前目录的约束、本机路径/服务设置的人工说明 | 第 1 期 |
| REPOSITORY | profile + 已验证 repository ID | 适用于此仓库所有工作副本的决策、约束、理由 | 第 2 期 |
| TASK | profile + task ID + repository/workspace 绑定 | 跨 session 的同一任务进展和临时例外 | 第 3 期，有 task 实体后 |

Kind 独立定义为 `PREFERENCE / DECISION / CONSTRAINT / REFERENCE / EPISODE`。当前待办和未完成工具仍在 session，不因为需要记住就塞进长期表。

`branchRef`、`pathPrefix`、环境标识是**附加适用条件**：存在的条件必须全部匹配。第一版 path 只支持归一化的目录前缀，拒绝 `..` 逃逸并按路径段匹配，避免 `src/a` 错匹配 `src/abc`。尚未确定相关文件时不默认装载所有模块记忆。

不为分支名创建永久记忆身份。分支可重命名、删除再创建，也可能 detached HEAD；TASK 使用稳定 ID，branch/ref、基准 commit 只是上下文证据。merge 不能自动把任务结论推广到整个仓库。

### 4.2 身份规则

1. **profile**：在外部数据根目录对应的数据库中分配稳定本地 profile ID，不用 cwd 或模型生成用户名。USER 表示此 profile 下跨项目，不意味着团队共享或云账户隔离。共享 WECODE_HOME 不提供多租户安全。
2. **workspace**：保留已有 UUIDv7 和不可变 root_path。将路径规范化收敛到统一注册入口；Windows 大小写、符号链接、目录别名用物理路径身份验证，发现疑似重复时先停用共享，不静默合并既有历史。
3. **repository**：以 profile + 已解析 Git common directory 的本地映射分配稳定 ID。同一 Git common directory 下的 worktree 可关联到同一 repository；记录其物理身份辅助识别目录删除后重建。Git 身份证据变化时失效映射并重新绑定，无法可靠确认时保持 WORKSPACE 隔离。
4. **worktree**：单独保存顶层工作副本路径和 Git directory 映射。同一 repo 不同 worktree 的本机事实/未提交变化不可共享。当前“从子目录启动就是不同 workspace”的产品语义保留。
5. **remote URL 不是身份凭证**：不同 clone、fork、URL 改名不能靠 remote hash 自动合并。默认不同 clone 隔离；后续显式关联才共享，迁移目录同样通过重新绑定处理。
6. **无 Git / 探测失败**：只使用当前 WORKSPACE；不借最近使用的 repo，不回退到所有项目的记忆。嵌套仓库/子模块按最近所属仓库单独识别。

Git 元数据探测不授予读写整个 repo 的权限。若 cwd 是 repo 子目录，加载父目录 AGENTS.md 或读取父级构建文件仍须经过明确的上下文读取范围配置；不能顺便把工具根路径改成 repo root。

### 4.3 查询不变量

先由宿主解析 `MemoryAccessContext`，得到当前 profile 允许的 namespace ID 集合；再由存储层同时约束：namespace、ACTIVE 状态、有效时间、未撤销版本、全部 branch/path/task 条件。最后才做关键词/语义排序。

REPOSITORY namespace 的匹配不应让带其他 workspace 限制的记录通过。禁止 `repository_id = ? OR workspace_id = ?` 这种漏掉附加条件的宽松查询。模型既不能指定 profile/repository ID，也不能用提示词请求解除过滤。

第 1 期用作用域过滤后的小集合排序即可。用户全局偏好与项目记录分配独立预算；代码技术栈不得仅因用户反复在某项目使用就晋升为 USER 规则。

## 5. 每类信息何时提取

| 信息 | 触发时机 | 执行方式 | 生效策略 |
|---|---|---|---|
| Session/工具状态 | USER、assistant、工具状态提交时 | Java 同步事务 | 提交成功即可恢复，不经 LLM 判断 |
| 显式指令文件 | workspace attach、相关文件读取/修改后、请求前版本检查 | 文件加载/哈希校验 | 按当前内容和目录范围生效 |
| 当前文件/环境事实 | 首次需要、相关文件变化、HEAD/配置变化 | 确定性提取/缓存 | 下次使用前检查；超时保留“未知”，不用旧事实伪装当前事实 |
| 显式 Memory 命令 | 拟议 `/memory add --scope ...` | 作用域和文本由用户直接给出，可不调用 LLM | 同步提交并返回真实结果 |
| 自然语言“记住/纠正” | USER 已提交后 | 明显显式请求走短同步提取；其余进入增量任务 | 当前轮原 USER 立即有效；只有提交成功才能宣称已长期保存 |
| 隐含偏好/项目决策 | 每个新 USER 事件登记待处理范围，回合结束后合并消费 | LLM 异步增量提取 | 自动候选默认待审核；未审核不注入 |
| 重复偏好 | 多个独立原始 USER 来源的候选聚合 | 语义归一化 + Java 去重 | 重复次数只增加证据，不自动提升作用域/权限 |
| 历史 EPISODE | 持久化明确回合结束原因后；未来任务结束时 | LLM 概括经过；Java 绑定结果证据 | 失败/未验证也可记录，不能生成“验证通过”的伪成功 |
| 过期/撤销/整理 | 检索前过滤；CLI 启动/空闲清理 | Java 生命周期状态机 | 逻辑失效即时；清理可延后 |
| Compaction | 手动/未来自动压缩完成 | 可唤醒已有积压任务 | 不是唯一触发点；不把摘要再当原始记忆证据 |

关键词 Gate 只能决定优先级，不能永久排除未命中消息。初期每个 USER 事件都会进入增量覆盖范围，允许把多个回合合并成一次请求；明确无候选也推进处理游标。工具大文本、模型 reasoning、已注入 Memory 不作为偏好提取来源。

把 `AgentLoop` 返回值改为拟议 `AgentRunResult(text, stopReason)`，至少区分 `FINAL_RESPONSE / MAX_STEPS / FAILED / CANCELLED`。FINAL_RESPONSE 只表示模型结束回复，也不能证明业务目标完成；当前无测试执行器，只能将用户报告与可验证工具观察分别标记。不要给 `markRunIdle()` 增加“任务已成功”的隐含语义。

## 6. LLM 与 Java 各自负责什么

提取流程：原始事件快照 → 限定证据输入 → LLM 候选 → 严格解析 → 宿主作用域解析 → 证据/语义校验 → 冲突检查 → 原子提交。

LLM 负责识别长期意图、区分目标和当前事实、提取原子内容/适用条件、提出 scope/kind 和可能重复项；不能直接选择真实身份、写数据库、决定授权或凭信心分数扩大作用域。

建议候选字段：`kind, requestedScope, content, applicability, intent, evidenceRefs, possibleDuplicateKeys`。`intent` 至少区分 `CURRENT_OBSERVATION / FUTURE_DECISION / DEFAULT_PREFERENCE / HISTORICAL_OUTCOME`；evidenceRefs 必须指向本次提供的事件及原文片段。多个主张应拆条，避免“Java 21 + 不改 schema + 使用本机端口”绑成无法独立更新的一条记录。

Java 执行：

- 严格 schema/枚举/大小验证，拒绝未知字段、越界来源、空内容、输出截断、tool call、非法 scope。
- 从宿主快照映射 namespace，不接受模型返回任意 ID；作用域不清楚时保持候选或保留在当前 session。
- 查证引用真的存在且角色正确。USER 中的代码块、引文、假设和否定仍需要语义判断；有引用不等于内容被用户认可。
- 仅显式陈述或已确认候选可直接 ACTIVE。推断不能因 `confidence=0.99` 自动成为长期指令。
- 模型输出不能修改 permission、trust 标志、系统规则或工具白名单。网页/工具结果中的“请记住”不得成为用户偏好。

**模型选择：调用职责分离，物理模型可相同。** 新增可配置的 memory Provider 档案，继续通过 `ChatModel` 调用；不新增一个拥有文件/命令工具的通用 AgentLoop。先用当前已配置模型验证质量，再用中文偏好、否定、纠正、跨项目隔离样例比较低成本模型，合格后切换。不存在“小模型天然足够可靠”的保证。

提取请求固定无工具、有限输入/输出、超时和重试次数。支持结构化输出的 Provider 优先使用 schema；其他 Provider 也必须宿主严格解析，最多一次修复请求，仍失败则保留任务待重试/检查。需先扩展 Provider 能力声明，禁止声称已约束输出而实际被默认方法忽略。

同步等待超时：报告“待保存”，不阻断普通编码任务；明确的存储写入失败不可反馈“已记住”。跨 Provider 自动回退默认关闭，避免未经配置把额外会话数据发送到别处。

## 7. 持久化、冲突与恢复

### 7.1 表设计建议

继续使用 `StoragePathResolver` 指向的外部 `wecode.db`。运行时记忆不落入项目 Git 目录。通过新增 Flyway migration 扩展，禁止修改已应用的 V1–V3。

| 表 | 主要字段/职责 | 阶段 |
|---|---|---|
| `memory_namespace` | id、profile_id、scope_type、scope_target_id、generation；归属唯一且不可变 | 第 1 期 |
| `memory_entry` | id、namespace_id、kind、canonical_key、content、intent、conditions、status、version、valid_from/until、supersedes_id | 第 1 期 |
| `memory_evidence` | entry_id、原始 message/tool/控制命令来源、片段定位、内容哈希、观测时间 | 第 1 期 |
| `memory_revision` | entry_id、版本、变更类型、原因、请求幂等键；审计与撤销 | 第 1 期 |
| `repositories` / `worktree_bindings` | repo/worktree 身份与 workspace 关联、验证证据 | 第 2 期 |
| `memory_job` | session_id、源事件区间、冻结 scope 快照、extractor_version、状态、attempts、lease_token、lease_until、retry_at | 第 2 期 |
| `memory_extraction_cursor` | session + extractor_version、已处理区间、scope generation | 第 2 期 |

Scope target 使用非空键及唯一约束，避免 SQLite 的 NULL 唯一性语义制造重复 namespace。限定字段组合通过 CHECK/类型模型和事务内父实体校验保证；不能仅把若干可空 ID 塞进 JSON。entry 记录生效状态，候选可用同表 `CANDIDATE` 表达，不必另建候选数据库。

`canonical_key` 是逻辑主题辅助键，不认为 LLM 能稳定生成全局唯一 key。同 namespace、同条件、同主张的精确哈希可去重；语义相似只用于提出合并候选，不直接删除旧记录。不同条件下的同名主题允许共存。

### 7.2 写入可靠性

第 2 期 USER/回合终止事件与对应 `memory_job` 在**同一个 SQLite 事务**提交；首次 USER 创建事务也必须覆盖。任务去重键包含 session、源区间、提取器版本、scope 快照版本。进程退出前不必完成模型调用，下一次启动可恢复待处理任务。

后台处理分三段：短事务领取任务并记录 lease → 事务外读取限定证据/调用模型 → 短事务校验 lease、namespace generation、旧 entry version 后提交候选/修订并完成任务。候选提交与游标推进原子完成；无候选也提交完成标记。迟到执行者不能写入，重试不得重复创建记忆。

同一 session 的消费按源序号处理；不同 session 的同主题更新使用 entry CAS。冲突时重新比较证据，不做“最后完成的请求覆盖一切”。Worker 不领取 Agent 执行权、不重放工具、不修改 RUNNING 状态；这不代表现有 session 多进程问题已经解决。初期每个进程只允许一个 Memory worker、有限队列和调用预算；任何连接不跨线程共享。

scope 在来源事件时冻结。后台任务执行时即使用户已切换 branch/workspace，也不能按新的当前目录重定向写入。Git 身份/映射代际改变时，旧任务只能进入过期/待审核，不能自动迁移归属。

### 7.3 冲突与失效

- 对当前事实：优先使用新的有效文件/工具观测，历史 Memory 只能作为线索。
- 对偏好：当前明确请求优先于适用的历史默认偏好；具体场景条件必须相符。
- 对目标决策：保留未来目标与当前状态的差异，不拿文件状态否决迁移意图。
- 对规则：宿主权限边界始终独立执行；Memory 不能把规则升级为系统授权。
- 对纠正：同一适用范围的明确新纠正可创建新版本并 SUPERSEDE 旧条目；“这次允许改 schema”是 task/session 例外，不撤销整个 repo 的长期规则。
- 对真正语义冲突：可由 LLM 建议，但不能靠时间或相似度机械覆盖；不确定则保留冲突候选并让用户编辑/确认。

状态建议：`CANDIDATE → ACTIVE → SUPERSEDED / EXPIRED / REVOKED`，被拒候选为 `REJECTED`。REVOKED 只可经新的显式操作恢复；历史 episode 以发生时间/结果展示，不冒充当前规则。稳定偏好不设置随意 TTL；本地环境观测使用短缓存；临时约束有截止日期时必须保存有效期。

拟议 `/memory forget` 需要立即停止检索，同时记录撤销墓碑及源事件处理边界，提升相关 generation，使在途任务无法复活旧记录。回溯重提取必须检查已撤销证据。此操作不自动删除原始聊天记录；“清除全部历史/备份中的内容”是另一项明确的数据删除能力。

## 8. 上下文组装与 Compaction 边界

新增 `AgentContextAssembler`，由 handler 传给现有 `ConversationMessageProvider`：

1. 从 `SessionConversationStore.loadMessagesForLlm()` 读取会话投影。
2. 加载宿主上下文规则、当前适用的显式指令与必要文件事实。
3. 解析当前 scope，查询匹配的 ACTIVE memory，过滤过期/撤销/不匹配条件。
4. 组装临时上下文块，输出完整 messages，再交给现有 `ContextWindowPolicy` 计费/估算。

固定 SYSTEM 只声明信任边界和使用规则，不嵌入 learned memory 原文。仓库指令和记忆由宿主标注来源、时间、适用条件，作为独立辅助上下文消息放在原始用户历史之前；不能伪造 tool result 或打断 assistant-tool 配对。当前 Role 无 developer，可采用宿主渲染的辅助 USER 消息，但类型必须在应用中另行标记为合成上下文，绝不成为用户证据。普通文本包装只是缓解手段，权限仍靠 Java 执行。

会话最初 SYSTEM 当前被固定持久化；新增 Memory 信任规则时要提供版本化的宿主运行期策略叠加，不能只改 PromptBuilder 后假定旧 session 也已更新。原始持久化 SYSTEM 留作历史，运行时策略版本单独记录。

Memory 默认在一个用户回合开始时选定版本快照，后续工具步骤保持一致，避免每次 chat 都变；文件事实在编辑后失效并刷新。每次请求仍检查撤销和硬作用域变化：删除必须及时生效，切分支/身份改变应重建上下文并标明变化，不延续旧任务记忆。

Memory 注入建议初始总预算约 2k **估算 token**，USER 偏好约 300，其余按任务相关性分配；这是起始配置，不是模型窗口保证。按完整条目裁剪，不把否定/适用条件截掉。当前 300k 固定窗口和字符/4 估算存在已知缺陷，新增上下文必须全部纳入估算。必要规则装不下时明确报告，不能悄悄丢掉后继续。

EPISODE 第 3 期按需检索少量条目；不全量常驻。即使后续加 embedding，也先执行命名空间及适用条件过滤。Memory 命中不意味着内容仍正确，执行前按需重新读取代码验证。

**动态上下文不写入 `session_message`，不进入 CompactionTranscript。** 压缩只改变会话历史投影，记忆从独立库重新装载；memory worker 只读原始允许的证据视图。这样可避免“注入 → 摘要 → 再提取 → 强化为事实”的循环。若未来需要请求复现，另存 request context manifest，记录 entry/version、事实哈希、策略版本，而非混入用户历史。

## 9. 模块安排与接口

新增 Maven `memory` 模块，先提供领域与端口，不引入外部 Memory 服务。

| 所在位置 | 拟议组件 | 职责 |
|---|---|---|
| `memory/model` | MemoryEntry、MemoryCandidate、MemoryNamespace、MemoryAccessContext | 类型、身份和状态约束 |
| `memory/service` | MemoryService、MemoryRetriever、CandidateValidator、ConflictPolicy | 检索、候选审核、修订、撤销 |
| `memory/extraction` | LlmMemoryExtractor、MemoryExtractionPolicy | 有界语义提取，无工具权限 |
| `memory/spi` | MemoryStore、MemoryEvidenceSource、MemoryJobStore | 持久化与只读证据接口 |
| `session/persistence/memory` | SQL adapters、Mapper、原始证据视图 | 初期复用现有数据库工厂和事务 |
| `agent/context` | AgentContextAssembler、InstructionLoader、WorkspaceFactsProvider | 在完整请求预算之前组合上下文 |
| `cli` | Memory 配置/命令、MemoryCoordinator、worker 生命周期、RepositoryResolver | 依赖装配、触发和产品反馈 |

初期依赖：`memory → llm,id`；`session → memory`（SQL 适配器实现端口）；`agent → memory,session`；`cli` 装配所有模块。**memory 不依赖 session、agent 或 cli**，历史通过 `MemoryEvidenceSource` 接口注入，避免循环依赖。

SQL adapter 暂放 session 是沿用当前 session 已拥有 workspace、SQLite、Flyway 的现实边界，不代表 Memory 领域属于 Session。数据库和 Mapper 注册仍只有一个入口，事件入队复用同一 SqlSession。以后公共持久化规模扩大时，再将基础设施及 SQL adapters 一起抽到 persistence 模块；第一版不为命名纯洁性做整仓重构。

`SessionInteractionHandler` 只编排触发和上下文，不承载提取 prompt/冲突 SQL。`AgentExecutionListener` 继续记录实际执行事实，不在工具完成回调内阻塞调用记忆模型。worker 任务接收证据 DTO，不共享可变 Session 对象。

## 10. 实施顺序与验收

| 阶段 | 交付内容 | 必须通过的验收 |
|---|---|---|
| 第 0 期：上下文边界 | InstructionLoader；事实来源标签；上下文组装；Memory 信任策略；修复压缩未回复 USER 问题 | 父级文件加载不扩大工具权限；旧 session 运行期策略可更新；压缩保留待执行请求 |
| 第 1 期：显式闭环 | memory 模块；USER/WORKSPACE namespace；add/list/show/forget；版本和证据；动态检索 | 跨项目偏好/约束隔离；重启可读取；撤销立即生效；不污染 compaction |
| 第 2 期：语义提取与仓库共享 | repository/worktree 身份；自然语言显式提取；原始 USER 增量候选；持久化 job、租约/幂等；候选 accept/reject；Provider 能力验证 | 同 worktree 家族共享而本机配置隔离；独立 clone 不自动合并；崩溃不丢任务；迟到写入不覆盖纠正；否定/引用不自动生效 |
| 第 3 期：任务与经验 | 明确结束原因；稳定 task 实体及跨 session 绑定；EPISODE；重复候选整理；必要时按需检索 | MAX_STEPS/FAILED 不被记为成功；分支重命名不串任务；merge 不自动升级 repo 事实 |

第 0 期不要求完成全部自动压缩路线或 `/resume` 才能开始第 1 期；但当前 `/compact` 的未完成请求与信任边界问题必须先处理。自动压缩仍遵循 [原排期](compaction-roadmap.md)，不顺便扩展成另一个 Memory 子系统。

第一版的自然语言自动归纳暂不开启；显式命令先建立存取/隔离/撤销闭环，第 2 期再接入 LLM。自动候选不生效不等于没有价值：用户可审核且不再重复录入，后续通过评测再决定哪些低风险类型可自动 ACTIVE。

专项测试应使用真实临时 SQLite 和 Git 仓库，少量固定模型响应验证协议即可，不生成泛滥 mock。重点覆盖：

- Java repo 与 Python repo、USER 条件偏好、同 repo 两个 worktree、本机 JDK 路径、子目录与嵌套 repo。
- 无 Git、Git 超时、remote 改名、分支删除重建、detached HEAD、目录重建及身份绑定变化。
- “以后升级 21”对比“当前 17”、引用别人说的话、否定纠正、仅本次例外、恶意工具文本。
- source/job 原子提交、领取后崩溃、重复消费、旧 worker 迟到、跨 session 冲突、forget 与在途提取竞争。
- 非法 JSON、额外字段、模型截断、无候选、输出预算未被 Provider 支持。
- compaction 后只注入一份记忆；Memory 及摘要不回流为提取证据；工具调用/结果配对完整。

记录候选接受率、错误 scope/事实率、跨范围召回、撤销复活、调用耗时/token 和积压量。隔离、幂等、撤销属于确定性门槛；小模型选择以语义样例评测为依据，而非指定未经验证的置信度阈值。

## 11. 外部实践核验与适用边界

- LangGraph 官方把 thread-scoped state/checkpointer 与跨 thread store 区分，并支持 hot path/background 写入；可借鉴边界和 namespace，不需要在 Java 项目引入该 Python 框架。[Memory overview](https://docs.langchain.com/oss/python/concepts/memory)
- Claude Code 官方区分人工指令与自动记忆；auto memory 按 repo 组织、worktree 共享，并使用精简入口和按需详情。文档没有在这里承诺独立 sidecar 的具体模型，不能据此断言所有产品都用小模型提取。[Claude Code memory](https://code.claude.com/docs/en/memory)
- Cursor 搜索索引仍可找到旧官方 Memories 页面中的 sidecar 描述，但本次直接访问重定向到当前文档首页；只能作为历史公开做法，不能保证当前产品仍按该架构运行。[原官方入口](https://docs.cursor.com/en/context/memories)
- 本机 OpenCode 源码 `F:/opencode/packages/opencode/src/session/instruction.ts` 已有全局/项目指令发现与加载，可借鉴显式指令层；WeCode 的 workspace 是更窄的工具访问边界，不能照搬其向 worktree 扩大的路径逻辑。

原 chat 关于其他产品的具体默认模型/内部实现未在本次逐项重新核验，不作为本设计的正确性前提。
