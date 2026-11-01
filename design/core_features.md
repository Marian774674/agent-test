# 四大核心功能：重新定义项目重心

> 状态：设计稿，待确认。定稿后按「实施顺序」拆计划。
> 本文取代 `prompt_init.md` 里的功能清单；冲突时以本文为准。

## 1. 一句话

这是一个**替你看着 QQ 的助手**：记住该记住的（备忘）、替你说该说的话（自动回复）、
告诉你错过了什么（消息整理）、帮你翻回去找到它（历史查询）。

## 2. 为什么改

原主线是「备忘 + 文件填写」。备忘录已经跑通，文件填写受限于表格模板的多样性，
投入产出比不划算，**冻结**（代码保留、不再迭代、不算核心功能）。重心移到下面四件事：

| # | 功能 | 现在的状态 | 要做到 |
|---|---|---|---|
| F1 | 备忘录 | ✅ 基本可用（建/改/取消/分级提醒/变更留痕） | 不再开发，只做回归 |
| F2 | 自动回复 | 🟡 有雏形：风格+事实已注入，@ 才触发 | 白名单开关 + 用户可写的子提示词 + 限流 + 风险降级草稿 |
| F3 | 消息整理 | ❌ 没有 | 打开前端时告诉你「你不在的时候群里发生了什么」，最该看的排前面 |
| F4 | 历史查询 | ❌ 没有 | 前端一句话提问，跨消息/文件/备忘/事实回答，能认出「小明」是谁 |

## 3. 目标与非目标

**目标**：四件事各自能独立解释清楚、独立验收；任何 AI 判断都能追溯到原始消息。

**非目标（本次不做）**
- 文件填写 / 表格回写——冻结，代码不删也不改。
- 多用户、多租户、鉴权体系——仍是单主、内网。
- 移动端原生 App——只做 Web。
- 自动回复「主动插话」——只在你 @ 它 或私聊时开口。

**反目标（做出来也算失败）**
- 自动回复在群里刷屏、或替你说出你绝不会说的话。
- 整理出来的「要点」跟原始消息对不上（幻觉）。
- 历史查询答得头头是道，但给不出任何一条证据。
- 每小时都在烧 token 却没人在看。

## 4. 现状盘点（已确认）

| 事实 | 出处 | 对设计的影响 |
|---|---|---|
| 群聊只有 `@机器人` 或前缀才进 Dify；私聊全进 | `components/event_listener/default.py:197-214` | 自动回复触发规则不用改 |
| 但**每条**群消息都已写 `message_log`（在触发判断之前） | 同上 `:172-175` | 消息整理不需要新增采集 |
| `message_log` 只有 `sender_id/chat_id/content`，**没有昵称和群名** | `design/database_schema.md:71` | 要加列，否则整理结果全是数字 |
| 插件已有 `profile()`（stylePrompt + facts）与 `open_memos` | `core/spring_client.py:45`、`core/event.py:42` | 自动回复的画像注入已具备 |
| Spring 只调 Dify 的 `/chat-messages`（Chatflow 应用） | `controller/MessageController.java` | 新增的整理/查询用**另一个 Workflow 应用** |
| 前端路由 `/` 直接跳 `/memos`，没有首页 | `src/router/index.ts` | 整理卡片和查询框要新开页面 |
| 已有 `@Scheduled` 调度范式（提醒扫描，默认关闭） | `schedule/MemoReminderScheduler.java` | 整理任务沿用同一范式 |

## 5. 全局设计

### 5.1 数据流

```
QQ ─► NapCat ─► 插件 ──┬─ 未 @：只写 message_log，结束
                       └─ @ / 私聊：写 message_log ─► Dify Chatflow（对话）─► 插件发 QQ
                                    │
                                    ▼
                          message_log（唯一事实源）
                                    │
              ┌─────────────────────┼──────────────────────┐
              ▼                     ▼                      ▼
      每小时整理任务          夜间归纳任务             查询时的检索
      （Dify Workflow）      （Dify Workflow）        （Dify Workflow）
              │                     │                      ▲
              ▼                     ▼                      │
      chat_digest_item           contact                POST /api/ask
              │                memory_note                   │
              ▼                     │                      │
      GET /api/digest ◄─────────────┴──────────────────────┘
              │
              ▼
         Vue 首页卡片 / 弹窗
```

**一条硬规矩**：所有 LLM 产出都必须带 `source_msg_ids`，能点回原文。整理、归纳、查询三条路都一样。

### 5.2 新增数据表

| 表 | 作用 | 谁写 | 谁读 |
|---|---|---|---|
| `chat_setting` | 每个群/私聊的开关：自动回复、纳入整理 | Vue | 插件 / Spring |
| `contact` | 人物表：`sender_id` ↔ 别名/身份，解决「小明是谁」 | 夜间任务 / 用户回答 | 整理 / 查询 |
| `memory_note` | 从聊天里抽出的事实：「小明的生日 = 11 月 11 日」+ 证据消息 | 夜间任务 | 查询 |
| `chat_digest_item` | 每群每小时的整理结果：摘要 + 重要度 + 证据 | 每小时任务 | Vue 首页 |

字段级设计见 §9，迁移脚本按现有 `db/migrations/` 的日期命名规则新增。

`message_log` 增三列：

- `sender_name VARCHAR(64) NULL`、`chat_name VARCHAR(128) NULL`——取不到就留 NULL，
  前端退化显示 ID，**不允许因为拿不到昵称就不记录**；
- `reply_kind VARCHAR(8) NULL`——只对 `direction='OUT'` 有意义：`TASK`（备忘/文件/操作结果、
  反问）还是 `CHAT`（日常对话的应答）。自动回复的限流按它统计（见 §8.1）。

`chat_setting.chat_id` 对私聊存 `sender_id`（`kind='PRIVATE'`），不置 NULL——
否则 `UNIQUE(chat_id, kind)` 对私聊失效（MySQL 允许多个 NULL），同一个私聊能被插成多行。

### 5.3 LLM 调用面：新增一个 Dify Workflow 应用「记忆与整理」

不塞进现有 Chatflow。三种任务共用一个应用、用输入 `task` 分流：

| `task` | 输入 | 输出 |
|---|---|---|
| `digest` | 一段群聊记录 + 画像 + 用户关注点 | `items[]`，每项 {summary, importance 1-5, keywords, mentions_me, source_msg_ids} |
| `extract` | 一段对话 + 已知 contact 列表 | `contacts[]`（新认出的人）、`notes[]`（事实三元组 + 证据 id） |
| `ask`（`stage=resolve`） | 问题 + `contact` 已知别名表 + `current_time` | `entities[]`（认出来的人）、`keywords[]`、`queries[]`（改写检索串）、`time_hint`、`time_from` / `time_to` |
| `ask`（`stage=answer`） | 问题 + 候选集（人物/事实/消息/文件/备忘） | `answer` + `used_ids[]` |
| `ask`（`stage=expand`） | 问题 + 累计候选摘要 + `current_time` | `expand_queries[]`、`expand_entities[]`、`expand_time_hint`、`expand_time_from` / `expand_time_to` |

`need_clarify` 不在 Dify 的输出里：resolve 只做语义抽取，认人歧义由 Spring 查 `contact` 后算出 `needClarify`（见 §7.3 与 `api_contract.md`）。

`ask` 一个任务按 `stage` 分多次调用（`resolve` 认人 / 改写 → `answer` 作答 → 答不出时 `expand` 换检索串，最多 3 轮），不是一个 prompt 里干两件事——
「先决定要不要反问」和「根据候选集生成结论」的失败方式完全不同，分开才能各自调。

**我的取舍**：一个应用而不是三个。省两份配置和两份重复的画像注入；代价是一个应用出问题时三个功能一起停，个人工具可以接受。若后续某个任务调参频繁互相打架，再拆。

Spring 侧新增 `client/DifyWorkflowClient`（`POST /v1/workflows/run`，blocking 模式），
配置项 `dify.workflow.base-url` / `dify.workflow.api-key` / `dify.workflow.timeout-ms`。

### 5.4 调度

| 任务 | 频率 | 内容 |
|---|---|---|
| 每小时整理 | `@Scheduled(cron = "0 5 * * * *")` | 取上一小时新增消息，按群切段 → `digest` → 写 `chat_digest_item` |
| 夜间归纳 | `@Scheduled(cron = "0 10 0 * * *")` | 取当天新增消息 → `extract` → 更新 `contact` / `memory_note` |
| 回溯补算 | 手动接口 | 首次启用或改规则后，把历史全量跑一遍 |

两个定时任务都通过 `app_setting` 里的开关控制（默认**关**，与提醒扫描一致）：
`digest_enabled`、`memory_job_enabled`。开之前先在日志里能看到「这次会处理 N 条消息」。

每小时整理与「进首页按需整理」（§6.1）是两条并列的触发路径，都写 `chat_digest_item`；
后者只在攒够了（条数/时长阈值）时补跑一次，避免用户刚打开页面却看到几小时前的旧内容。

**幂等**：整理任务按 `(chat_id, period_start)` 唯一键写入，重跑覆盖不追加；
归纳任务按 `(subject_sender_id, predicate, value)` 去重，同值不重复插。
失败只记日志、下次从上次成功的 `period_end` 继续，不重试、不补偿。

## 6. F3 消息整理

### 6.1 触发与展示

- 每次进「首页」→ `POST /api/digest/refresh`：后端拿「上次整理时间」到现在的窗口，
  窗口内消息数 **> `digest_refresh_min_messages`（默认 100）** 或间隔
  **> `digest_refresh_min_minutes`（默认 60）**，任一满足就对勾选的群跑一次整理并把锚点推到当前；
  都没到就什么都不做，窗口继续累积（两个阈值在「群设置 → 整理偏好」里改）。
  锚点存 `app_setting.last_digest_run`，与用户点「都看过了」的 `last_digest_seen` 是两个概念。
- 随后 `GET /api/digest?since=<上次离开时间>`；
- 首页顶部一张「你不在的这段时间」卡片，按重要度倒序，默认折叠到 5 条；
- 有 `importance >= 4` 的项时**对那条弹窗一次**（同一时间段只弹一次，记在本地存储）；
- 卡片底部「都在这里了」按钮 → `POST /api/digest/seen` 更新 `app_setting.last_digest_seen`。

「上次离开」存在服务端（`last_digest_seen`），不用前端时间，避免换设备重复推送。

### 6.2 只看你勾了的群

`chat_setting.digest_enabled` 为 1 的群才进整理。**私聊不整理**（按你的决定）。
第一次打开该功能时，页面列出机器人所在的群让你勾——群列表从 `message_log` 里
`DISTINCT chat_id` 反推，不需要额外调 NapCat。

### 6.3 「我可能感兴趣」怎么判

三层叠加，最终由模型合成为一个 1–5 的重要度：

1. **规则层（确定性的，先过滤）**：@ 了我、出现我的名字、带时间词（明天/周X/截止/几点）、
   带文件、来自 `contact.importance >= 2` 的人 → 保底进候选。
   「我的名字」来自 `app_setting.owner_aliases`（逗号分隔的昵称/称呼，设置页可编辑），
   连同 `owner_sender_id` 一起作为识别依据——群里不会有人用 QQ 号叫你。
2. **画像层**：注入 `style_profile.profile_prompt` + `profile_fact`。
3. **关注点层**：`app_setting.digest_focus_prompt`——你写的一段话，原样注入。
   例：「我关心实习招聘、课程通知、要我交钱的；游戏群里谁赢谁输不用告诉我」。

摘要一律要求「谁说了什么」，**不许出现原文没有的信息**；模型给的 `source_msg_ids`
必须在输入里存在，否则该项丢弃（防幻觉的硬校验，在 Spring 做，不靠提示词自觉）。

## 7. F4 历史查询

### 7.1 交互

`/ask` 页面，一个对话框。你问 → 它答（结论 + 2–3 条原文证据）。
人物认不出来时**反问**，你答完它记住，再接着答原问题。

```
你：我的同学小明的生日是什么时候
它：我还不知道「小明」是谁，能说说是哪个群的谁吗？
你：就是三班群里那个
它：记下了。小明的生日是 11 月 11 日。
    证据：三班群 · 10-28 21:03 · 小明：「11 月 11」
```

### 7.2 为什么是混合检索：结构化事实 + 七路召回 + RRF

你举的例子——问「你的生日是多少」，答「11 月 11」——字面和「生日」根本不挨着。
纯向量检索在这种**跨句、跨轮、需要归属到人**的场景上不可靠；而数据量小（个人 QQ），
每天把新消息过一遍、抽成「谁 · 什么属性 · 什么值 · 出处」，查的时候就是结构化事实精确命中。

但只靠夜间归纳也不够：换说法/代词兜不住、事实归属未知或跨会话兜不住、两跳推理兜不住。
所以结论不是二选一，而是**混合检索**：

- **结构化事实**（`memory_note`）负责精确命中，并给出可回溯的证据 id；
- **七路召回**（`note / msg-person / msg-mention / msg-lexical / msg-semantic / memo / file`）
  负责兜底：`message_log` 的 `FULLTEXT(ngram)` 做字面召回，`message_embedding` 做语义召回；
- **RRF** 融合去重，再按时间软分段与字符/条数预算截断，交给 Dify 作答并校验 `used_ids`。

实现与取舍见 [`intelligent_retrieval.md`](./intelligent_retrieval.md) §1.3、§7。
「为什么不只做向量」的论证仍然成立，只是它不再是全部。

**这是你的授权范围**（「搭知识库也好、每天 0 点扫一遍也好」），我选了「夜间结构化 + 在线混合召回」。

### 7.3 查询流水线

```
问题
 ├─ 1. 查询理解（Dify ask, stage=resolve）：抽 entities / keywords / queries / time_hint / time_from / time_to
 │      Spring 再拿 contact 精确认人：≥2 个 sender_id → needClarify（附候选，不检索）；
 │      唯一 → 主体；没有 contact → 记下称呼，稍后按「记录里出现过几次」决定是否反问
 ├─ 2. 检索前补跑向量：indexMissing(≤500)，剩余缺口如实写进 scope.lag
 ├─ 3. 七路召回（Spring）：note / msg-person / msg-mention / msg-lexical / msg-semantic / memo / file
 │      单路失败只丢这一路（scope.channels 置 null），不冒 500
 ├─ 4. RRF 融合：跨轮累计、按通道内名次计分、时间软分段、30000 字符 / 150 条预算
 ├─ 5. 作答（Dify ask, stage=answer）：问题 + 候选集 → 结论 + used_ids
 ├─ 6. 校验（Spring）：used_ids ⊆ 累计候选集，否则 degraded=true，只给证据、不给结论
 └─ 7. 答不出且还有轮次预算 → Dify ask, stage=expand 换新检索串 / 实体，回到 3；最多 3 轮
```

`needClarify` 是 Spring 算出来的结果，不是 Dify 的输出；resolve 失败时按原问题整句检索。
候选集为空也会跑一次 `expand`（可能只是措辞问题），仍为空才判定「没找到」。
完整终态表与失败降级规则见 [`intelligent_retrieval.md`](./intelligent_retrieval.md) §7.6–§7.8。

**反问是无状态的。** `/api/ask` 不带会话，前端自己保存上一轮的
`{question, alias, candidates}`，用户回答后带上 `clarify` 字段再发一次：

```json
{"question":"我的同学小明的生日是什么时候","clarify":null}
{"question":"我的同学小明的生日是什么时候",
 "clarify":{"alias":"小明","senderId":"123456","from":"就是三班群里那个"}}
```

选了人 → 写 `contact(source=USER)` → 用原问题带 `clarify` 重跑一次 resolve（这次直接命中）。
不引入 `ask_session` 表，没有过期清理，刷新页面丢失反问上下文——可以接受，
代价是用户得重说一遍，而不是后端攒一堆僵尸会话。

**证据 id 一律带类型前缀**：`msg:123`、`note:7`、`file:45`、`memo:12`。
四张表各自自增，裸数字在模型输出、子集校验、前端跳转三处都会撞车。

`source_msg_ids` / `msg:` 里的数字是 **`message_log.id`**（本库自增），
不是平台消息 id——插件此刻的入队负载里根本没有平台 id，夜间任务读的是已落库的行。

**候选集来源与上限**（与 [`intelligent_retrieval.md`](./intelligent_retrieval.md) §7.3 一致）

| 通道 | 怎么选 | 单路上限 |
|---|---|---|
| `note` | 主体 = 解析出的 `sender_id`；keyword 命中谓词 / 值 | 200 + 100 |
| `msg-person` | 发送者 = 解析出的人，时间倒序 | 200 |
| `msg-mention` | 每个 entity 走一次全文，不按会话 / 发送人过滤 | 100 / 实体 |
| `msg-lexical` | 每条 query 走一次全文 | 100 / query |
| `msg-semantic` | 前 3 条 query 的向量平均后扫索引；语义不可用时该路不跑 | 100 |
| `memo` | 标题 / 正文 keyword LIKE | 50 |
| `file` | 文件名 keyword LIKE | 30 |

总预算 30000 字符 / 150 条，在 RRF 融合后、交给模型前统一截断（多轮时对累计集每轮做一次）。
时间软分段把落在 `time_from` / `time_to` 内的候选整体前置，区间外仍保留，不做硬过滤。

**谓词受控词表**：`extract` 只能从下面这张表里选 `predicate`，另给一个自由文本 `detail`：

`生日 / 姓名 / 专业 / 班级 / 学号 / 住址 / 电话 / 时间安排 / 偏好 / 关系 / 其他`

理由：谓词自由发挥时，「生日」「出生日期」「哪天生的」会分裂成三条，查的时候对不上。
固定词表让 SQL 能精确命中；词表覆盖不到的塞 `其他`，最后一步生成时把所有
`其他` 类 + 命中的 note 一起交给模型做语义筛——**语义匹配留在最后一步，
不靠 SQL 硬凑**。

### 7.4 人物表怎么长出来

- **自动**：夜间 `extract` 从对话里认（「我是小明」「@小明 你生日几号」），写 `contact(source=AUTO)`。
- **用户回答**：`source=USER`，优先级高于 AUTO。
- **手动兜底**：记忆页（`MemoryView`）加「认识的人」区块，可增删改。

**去重**：`UNIQUE(sender_id, alias)` + `INSERT ... ON DUPLICATE KEY UPDATE`（只更新 `relation/note`）。
**USER 保护**：夜间任务的所有写操作都带 `WHERE source <> 'USER'`，用户认过的绝不被覆盖。

**冲突不需要额外的表**：同一个 `alias` 落在两个 `sender_id` 上就是两行，
查询时 `SELECT ... WHERE alias = ?` 拿到 2 条即视为冲突 → 触发反问。
这样「未解决的冲突」天然存在，不需要状态列、不需要清理任务；
用户回答后要么新增一行 `source=USER`（并且以后 resolve 优先取 USER 行），
要么在记忆页把错的那行删掉。

## 8. F2 自动回复（最后做）

### 8.1 开口条件（三层，缺一不可）

> **先厘清一件事**：`auto_reply_enabled` 关掉的是「**它替你说日常话**」，
> 不是「它干活」。@ 机器人这一条消息仍然要送 Dify——备忘的建/改/取消、
> 文件任务、以及「哪条备忘？」这类反问，都走同一次调用。
> 如果拿它去卡调用，默认关就等于把群里的备忘功能一起关了，直接违反 §2。

所以判定拆成「**做不做**」和「**说不说**」：

1. **做**：会话消息照旧送 Dify，Dify 照旧执行 MEMO_OP / 建备忘 / 建草稿。这一层不受开关影响。
2. **说**：Chatflow 的决策信封多一个字段 `send_kind`：
   - `TASK` —— 备忘/文件/操作结果、要素不全时的反问 → **总是发**（和今天一样）；
   - `CHAT` —— 日常对话的应答 → 只有 `auto_reply_enabled = 1` **且总开关 `app_setting.reply_enabled` 没被关掉**时才发。
   这个区分是语义判断，属于 Dify；「发了之后怎么记、怎么限流」属于插件和 Spring。
3. **限流**：只对 `CHAT` 生效。同一会话每小时最多 `app_setting.reply_rate_limit`（默认 20）条，
   统计口径 = `message_log` 里该会话最近一小时 `direction='OUT' AND reply_kind='CHAT'` 的行数。
   超了这条 `CHAT` 不发、写一条 `file_store.debug`，**不影响同一轮的 `TASK` 输出**。

群里只有 `@机器人` 或带前缀才算触发，私聊直接算；**不加「主动插话」**——这条不变。

**总开关**（设置页「自动回复」分区顶部）：一键把 `CHAT` 全停，不用一个个群去关。
存 `app_setting.reply_enabled`，**没配过 = 开**——它是紧急闸，不能因为升级把已经
逐个打开的会话顺手关掉。判定在 Spring 的 `reply-context` 里做（`enabled = 总开关 AND 会话开关`），
插件不用改：它本来就只认一个 `enabled`。`TASK` 照旧不受影响。

`send_kind` 是决策信封的新字段，按仓库规矩**先改 `design/agent_contract.md` 再动代码**
（`agent_contract.md:5`「任何一侧改字段，先改本文件」）。插件拿不到这个字段时按 `TASK` 处理，
保证旧插件新 Dify、或新插件旧 Dify 都不会静默丢消息。

### 8.1.1 为什么不在插件里数一个计数器

`message_log` 已经逐条记录 OUT，`reply_kind` 一列就能把「日常应答」和
「拦截回复 / 草稿发送 / 提醒推送」分开；再维护一张计数表就要处理窗口滑动和重启丢状态。
宁可每条问一次 SQL（一行 `COUNT`，走 `idx_sender_created`）。

### 8.2 注入什么

沿用现有 16 个输入，再加三个：

| 新输入 | 内容 | 来源 |
|---|---|---|
| `reply_prompt` | 你写的那段子提示词 | `app_setting.reply_prompt` |
| `recent_messages` | 该会话最近 15 条（含别人说的） | `GET /api/chat-settings/reply-context` |
| `reply_rules` | 固定约束的机器可读版（不写进你的子提示词） | 插件常量 `core/reply_rules.py` |

三份材料（开关、限流、人设、最近消息）由 `GET /api/chat-settings/reply-context`
**一次取齐**：它们同属一轮提示词输入，分两次取会出现「开关是新的、计数是旧的」这种
自相矛盾的快照，还多一个失败点。`reply_rules` 是 Dify 输入拼装的一部分，所以放插件
（与 `open_memos` 的 `json.dumps` 同一层），不进 Spring。

**私聊按对方 QQ 计**：私聊的 `message_log.chat_id` 是 NULL，`sender_id` 才是会话键；
`chat_setting` 的私聊行也存对方 QQ。`listAll` 会从日志里把私聊对象一并列出来，
否则这个开关在界面上无从打开。

`recent_messages` 是自动回复「像人」的关键——现在 Dify 只能看到被 @ 的那几句，
它根本不知道群里刚才在聊什么。这段数据本来就躺在 `message_log` 里。

### 8.3 风险降级

Chatflow 的回复分支多输出一个 `needs_confirm` 布尔：

- `false` → 插件直接发（现状）；
- `true` → 不发，写 `draft(type=REPLY)`，你在草稿中心点一下才发。

模型判 `true` 的情形（写进提示词，但阈值/策略不进提示词）：
**替你做承诺、定时间、谈钱、涉及第三方评价、你被直接点名要求答复**。
这是语义判断，属于 Dify 的职责；「草稿怎么存、怎么确认」属于 Spring。

### 8.4 你写的子提示词

一段话，存 `app_setting.reply_prompt`，前端设置页一个多行输入框，附三个例子。
它被注入为「主人的额外要求」，**优先级低于固定约束**（不透露是机器人、不承诺、
不编造事实）；两者冲突时以固定约束为准，并在提示词里明确写出这条优先级。

## 9. 表结构草案

```sql
-- 每个会话的开关；没这行 = 全关
CREATE TABLE `chat_setting` (
  `id`                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `chat_id`             BIGINT UNSIGNED NOT NULL COMMENT '群号；私聊存对方 sender_id',
  `kind`                VARCHAR(8)  NOT NULL COMMENT 'GROUP|PRIVATE',
  `chat_name`           VARCHAR(128) NULL,
  `auto_reply_enabled`  TINYINT(1)  NOT NULL DEFAULT 0,
  `digest_enabled`      TINYINT(1)  NOT NULL DEFAULT 0,
  `created_at`          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_chat` (`chat_id`, `kind`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话开关';

-- 人物表
CREATE TABLE `contact` (
  `id`          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `sender_id`   BIGINT UNSIGNED NOT NULL,
  `alias`       VARCHAR(64)  NOT NULL COMMENT '用户/模型认出来的称呼',
  `relation`    VARCHAR(64)  NULL COMMENT '同学|老师|室友…',
  `importance`  TINYINT      NOT NULL DEFAULT 0 COMMENT '0=普通 1=一般 2=重要，整理排序用',
  `note`        VARCHAR(255) NULL,
  `source`      VARCHAR(8)   NOT NULL DEFAULT 'AUTO' COMMENT 'AUTO|USER',
  `created_at`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sender_alias` (`sender_id`, `alias`),
  KEY `idx_alias` (`alias`),
  KEY `idx_sender` (`sender_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='认识的人';

-- 从聊天里抽出的事实
CREATE TABLE `memory_note` (
  `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `subject_sender_id` BIGINT UNSIGNED NULL COMMENT '主体；空=关于主人自己',
  `predicate`         VARCHAR(64)  NOT NULL COMMENT '受控词表，见 §7.3',
  `value`             VARCHAR(255) NOT NULL,
  `happened_at`       DATETIME     NULL,
  `source_msg_ids`    VARCHAR(512) NOT NULL COMMENT '逗号分隔，必须能回查',
  `confidence`        DECIMAL(3,2) NULL,
  `created_at`        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_subject_pred` (`subject_sender_id`, `predicate`),
  KEY `idx_predicate` (`predicate`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='事实归纳';

-- 每小时整理结果
CREATE TABLE `chat_digest_item` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `chat_id`       BIGINT UNSIGNED NOT NULL,
  `chat_name`     VARCHAR(128) NULL,
  `period_start`  DATETIME NOT NULL,
  `period_end`    DATETIME NOT NULL,
  `summary`       VARCHAR(512) NOT NULL,
  `importance`    TINYINT      NOT NULL COMMENT '1-5',
  `keywords`      VARCHAR(255) NULL,
  `mentions_me`   TINYINT(1)   NOT NULL DEFAULT 0,
  `source_msg_ids` VARCHAR(512) NOT NULL,
  `created_at`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_chat_period` (`chat_id`, `period_start`),
  KEY `idx_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消息整理';
```

`app_setting` 新增的键：`digest_focus_prompt`、`reply_prompt`、`reply_rate_limit`、
`last_digest_seen`、`digest_enabled`、`memory_job_enabled`、`owner_aliases`。

## 10. 实施顺序

1. **数据底座**：`message_log` 加三列（昵称/群名/`reply_kind`）、四张新表、`app_setting` 新键、
   插件写昵称与群名。做完先只跑日志，确认数据进得来。
2. **Dify 工作流「记忆与整理」** + Spring 的 `DifyWorkflowClient` + 手动触发接口。
   先用一小段假消息验证 `digest` / `extract` 的输出形状，再接真数据。
3. **F3 消息整理**（每小时任务 + `GET /api/digest` + Vue 首页卡片与弹窗）。
   这是第一个用户能看见的东西，先让它跑起来。
4. **F4 历史查询**（夜间归纳 + `/api/ask` + 反问闭环 + Vue 对话框 + 记忆页人物区）。
5. **F2 自动回复**（`chat_setting` 开关 + 子提示词 + `recent_messages` + 限流 + 草稿降级）。
   ✅ 已实现（2026-10-30）：插件门控 `core/reply_gate.py`、契约 `send_kind` / `needs_confirm`、
   `GET /api/chat-settings/reply-context`、设置页子提示词与限流。**上线还差人工把
   `design/QQAssistant.chatflow.yml` 重新导入 Dify 并发布**，之后才是真机验证。
6. **文档收敛**：README 的核心功能章节改写成这四件；文件填写标为「冻结」。

每步都能独立验收，做完一步能停。自动回复放最后，是因为它唯一会对外产生副作用。

## 11. 风险与取舍

| 风险 | 处理 |
|---|---|
| LLM 摘要编造 | `source_msg_ids` 硬校验，对不上就丢；证据在前端可点开原文 |
| 每小时任务烧 token | 默认关；只整理勾选的群；单小时单群超过 200 条就只取最近 200 条并记录截断 |
| 自动回复说错话（用你的号） | 白名单默认关 + 每小时上限 + 高风险走草稿 + 首周只在测试群开 |
| 反问死循环（用户也说不清小明是谁） | 最多反问一次，第二次直接给候选列表让人点，仍不行就答「没查到」 |
| 夜间任务跑一半崩了 | 记录已处理到的 `period_end`，下次从这里继续；不重试、不补偿 |
| 群名/昵称拿不到 | 允许 NULL，UI 退化显示 ID；不影响整理逻辑 |
| 查询候选集太大 | 七路各有单路上限；RRF 融合后按 30000 字符 / 150 条统一截断再交给模型（见 [`intelligent_retrieval.md`](./intelligent_retrieval.md) §7.3） |

## 12. 明确不做

- ~~不做向量数据库。理由见 §7.2；`memory_note` 已经是「向量化之后的答案」。~~
  **已推翻**：现采用混合检索（结构化事实 + 七路召回 + RRF），见
  [`intelligent_retrieval.md`](./intelligent_retrieval.md) §1.3。`memory_note` 只是其中一路，不是全部。
- 不做主动插话式自动回复。
- 不做私聊的消息整理。
- 不做文件填写的任何改动（含删除）。
- 不做多用户/权限。

## 13. 已拍板（2026-10-30）

1. **新增 `/home` 当首页**，`/` 指向它；底部导航四个：首页 / 备忘 / 草稿 / 更多。
2. **自动回复第一周只在你自己的测试群开**，跑稳了再扩到别的群。
3. **夜间归纳每天 0:10 处理前一天新增**；首次启用时把历史全量补算一次
   （`POST /api/memory/rebuild`），接受一次跑几分钟与相应的 token 开销。
