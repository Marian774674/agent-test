# 「记忆与整理」Dify Workflow 契约

对应文件：`design/QQAssistant-memory-workflow.yml`（Workflow 模式，直接导入 Dify 即可）。

> **为什么不改 `QQAssistant (2).yml` 而是新建同目录文件**：Dify 一个 DSL 文件只描述一个应用，
> 而 `QQAssistant (2).yml` 是 `mode: advanced-chat` 的对话应用（走 `/v1/chat-messages`）。
> 整理任务走的是 Workflow 接口 `/v1/workflows/run`，两者不可能装进同一个 YAML。
> 新文件是**以那份导出为模板**写的：同 DSL 版本（0.7.0）、同 tongyi 插件依赖、同模型
> `qwen3.8-flash`，只把图换成 Workflow。原文件保持原样，两个应用各自导入。

## 1. 调用契约（Spring 侧：`DifyWorkflowClient`）

同一个应用、同一个 Key，用 `inputs.task` 分派四种活。`run(task, extra)` 会自动把 `task` 塞进 inputs，
其余键由调用方给；Start 节点必须把下面每一个键都声明出来，否则 Dify 直接 400。

| task | 调用方 | 额外输入 | 读的 outputs |
|---|---|---|---|
| `digest` | `ChatDigestServiceImpl` | `chat_name` `messages` `profile` `focus` | `items` |
| `extract` | `MemoryJobServiceImpl` | `messages` `known_contacts` `owner_aliases` | `contacts` `notes` |
| `ask`（`stage=resolve`） | `AskServiceImpl` | `question` `known_contacts` `owner_aliases` | `entities` `keywords` `time_hint` |
| `ask`（`stage=answer`） | `AskServiceImpl` | `question` `candidates` `time_hint` | `answer` `used_ids` |

所有 outputs 都是**字符串**：数组/对象一律 `json.dumps` 成字符串再输出。Spring 解析失败时按
「模型这次什么也没说」处理——**不写库、不推进游标**，而不是把半截数据塞进去。

### 1.1 digest

```
POST {dify.workflow.base-url}/workflows/run
Authorization: Bearer {dify.workflow.api-key}

{
  "inputs": {
    "task": "digest",
    "chat_name": "三班群",
    "messages": "12|[@我]|班主任|14:03|明天下午三点开班会\n13||小明|14:05|收到",
    "profile": "小明,明哥",
    "focus": "只关心实习和考试相关"
  },
  "response_mode": "blocking",
  "user": "spring"
}
```

响应取 `data.outputs.items`（**字符串**，内容是一个 JSON 数组）：

```json
[{"summary":"班主任提醒明天下午三点开班会","importance":4,"keywords":"班会,通知","mentions_me":false,"source_msg_ids":[12]}]
```

- `source_msg_ids` 必须是输入里出现过的 `message_log.id`。Spring 会逐项校验，**任何一项证据不在本次窗口内，整项丢弃**（不是截断、不是修补）。
- `importance` Spring 会夹到 1–5；`mentions_me` 按布尔解析。
- `items` 解析失败时 Spring 记日志并**不删除**该时段旧数据；只有「解析成功但没有条目」才会清空该时段。

### 1.2 extract

`inputs`：`messages`（行格式见 §2.2）、`known_contacts`（`[{"senderId":"123","alias":"阿黄","relation":"室友"}]` 的 JSON 字符串，最多 200 条）、`owner_aliases`。

```json
{"contacts":[{"sender_id":123,"alias":"阿黄","relation":"室友"}],
 "notes":[{"subject_sender_id":123,"predicate":"生日","value":"11月11日","happened_at":"","source_msg_ids":[12],"confidence":0.9}]}
```

- `predicate` 是受控词表：生日 / 姓名 / 专业 / 班级 / 学号 / 住址 / 电话 / 时间安排 / 偏好 / 关系 / 其他。
  Spring 遇到词表外的值一律落 `其他`，不报错。
- `subject_sender_id` 为空 / `null` = 说的是主人自己。给了值却读不成 QQ 号的（比如写成昵称），
  **整条 note 丢弃**——不能退化成 `null`，那会把别人的事实记到主人头上。
- `subject_sender_id` **可以取本批发言人的 `sender_id`，也可以取 `known_contacts` 里、
  且其任一 alias 在证据消息正文中出现过的联系人**。后者用于「小红说『小明生日 11 月 11』」
  这类被提到但本批没发言的人；说主人自己时仍填 `null`。
- 这条规则由 Spring 在 `MemoryJobServiceImpl.write` 里硬校验：主体不在「本批发言人 ∪
  本批 `known_contacts`」、或（非本批发言人时）其 alias 未出现在**本条 note 自己的**
  `source_msg_ids` 正文中 → **整条 note 丢弃**，记日志、不写库。提示词只负责倾向，校验负责正确。
- 两个 id 字段都接受数字或数字字符串（`"123"`）：模型经常把 QQ 号写成字符串，解析节点统一转成 int。
- `source_msg_ids` 必须落在本批次内；**任何一项证据不在批次内，整条 note 丢弃**。
  `sender_id` 不在本批次内的 contact 同样丢弃（防模型编 QQ 号）。
- 同一 `(主体, 谓词, 值)` 在 Spring 侧去重：批次内去重，且与库里已有行比对。

### 1.3 ask：为什么分两段

认人必须走 SQL（`contact` 表）而不是模型的记忆，否则「阿黄」是谁全凭模型猜；而候选集又要先认完人
才查得出来。所以拆成两次调用：

1. `stage=resolve`：模型只抽 `entities`（人名）与 `keywords`（检索词）和 `time_hint`。Spring 拿
   `entities` 去 `contact.alias` 精确匹配——0 条 = 不认识（前端/机器人反问），≥2 条 = 重名（让用户选），
   1 条 = 认下。用户选了之后 Spring 写一条 `source=USER` 的 contact，这个别名以后不再问。
2. `stage=answer`：Spring 把候选集压成 `类型:id|标签|内容` 的行（msg / note / memo / file，
   总量截断到约 12000 字符）交给模型，模型只能在这堆行里作答，并用 `used_ids` 标出处。
   Spring 再验一次 `used_ids ⊆ 候选集`：不在里面、或者有结论却零引用 → **结论作废**，只把证据回给用户。

## 2. 输入行格式

### 2.1 digest

```
id|[@我]|昵称|HH:mm|正文
```

- 第二列是 `[@我]` 表示这条点名了用户（由 Spring 按 `app_setting.owner_aliases` 逗号分隔做**字符串包含**判断，确定性、不交给模型猜）。
- 正文压掉换行、截 200 字；一次最多送最近 **200** 条。

### 2.2 extract

```
id|sender_id|昵称|群名|HH:mm|正文
```

- 每行自带群名：一个批次会横跨多个群，单值 `chat_name` 会让模型把不同群的消息当成同一段对话。
- 正文压掉换行、截 200 字；一次最多 **200** 条；游标是 `message_log.id`。
- `sender_id` 是模型输出 `subject_sender_id` / `contacts[].sender_id` 的唯一合法来源。

## 3. 图结构

```
start ─► is_digest ─true─► digest_llm ─► parse_digest ─► end_digest(items)
                 └false─► is_extract ─true─► extract_llm ─► parse_extract ─► end_extract(contacts, notes)
                                  └false─► is_resolve ─true─► resolve_llm ─► parse_resolve ─► end_resolve(entities, keywords, time_hint)
                                                    └false─► answer_llm ─► parse_ask_answer ─► end_answer(answer, used_ids)
```

- `is_digest`：`task is digest`；`is_extract`：`task is extract`；`is_resolve`：`stage is resolve`。
  `task` 只剩这三种取值，`ask` 的第二段（`stage=answer`）落在最后一条兜底支路上。
- 每个分支都是「LLM → parse(code) → end」：代码节点只做**形状清洗**（剥围栏、`json.loads`、
  丢掉没证据的条目），不做事实校验，也不碰数据库。
- 结束节点的输出名必须全局唯一（`items` / `contacts`,`notes` / `entities`,`keywords`,`time_hint` /
  `answer`,`used_ids`），Dify 的「发布」检查清单会卡这件事。
- `parse_ask_answer` 在有结论却零引用时会把 `answer` 清空——这条规则在 DSL 里也钉一遍，
  免得模型绕过 Spring 的校验直接说话。

## 4. 提示词（与 YAML 内一致）

### 4.1 digest

System：

```
你是群聊记录整理器。用户（下称「我」）不在线时群里发生了很多事，你要把值得他知道的挑出来，每条一句话说清「谁说了什么」。

messages 每行一条消息，格式是：
id|[@我]|昵称|时间|内容
第二列是 [@我] 表示这条消息点名了我。

只输出一个 JSON 数组，不要 Markdown 代码块、不要任何解释：
[{"summary":"班主任提醒明天下午三点开班会","importance":4,"keywords":"班会,通知","mentions_me":false,"source_msg_ids":[12]}]

规则：
1. summary 不超过 60 字，必须写明是谁说的、说了什么；同一个人围绕同一件事连发的几条合并成一条，source_msg_ids 列出全部证据。
2. importance 取 1–5：5=要我立刻处理（时间地点变更、截止时间、点名要我回复）；4=与我直接相关或必须知道；3=一般通知；2=闲聊但有信息；1=可忽略。
3. keywords 最多 3 个，逗号分隔，不要带 #。
4. mentions_me 为 true 当且仅当这条的证据行里有 [@我]。
5. source_msg_ids 只能取输入每行开头的 id（整数数组），不许编造、不许用行号代替；拿不出证据的条目不要输出。
6. 带 [@我] 的行必须各自产出一项（同一件事可以合并），mentions_me 为 true，且 importance 不低于 4。
7. 没有值得说的内容就输出 []。「哈哈哈」「收到」「+1」这类口水话不要单独成条。
8. 不许出现输入里没有的人名、时间、事项；不确定就在 summary 里写「有人提到」，并降低 importance。
9. focus 非空时按 focus 关心的点取舍和排序；focus 为空时按「有时间地点的通知 > 要我做的事 > 提到我」取舍。
```

User：

```
群：{{#start.chat_name#}}

我的身份与别名：{{#start.profile#}}

我关注：{{#start.focus#}}

messages:
{{#start.messages#}}
```

### 4.2 extract

完整文本在 YAML 的 `extract_llm` 节点里，这里只记它必须钉住的四条：

1. 行的六段格式 `id|sender_id|昵称|群名|HH:mm|正文`，以及「每行自带群名」意味着什么。
2. `predicate` 的受控词表逐字列出（生日 / 姓名 / 专业 / 班级 / 学号 / 住址 / 电话 / 时间安排 / 偏好 / 关系 / 其他），
   词表外一律用「其他」。
3. `subject_sender_id` 可以取输入行里的 `sender_id`（本批发言人），也可以取 `known_contacts` 里、
   且其 alias 在证据消息正文中出现过的联系人；说主人自己时填 `null`。
   Spring 会硬校验这个归属（见 §1.2），不满足即整条丢弃。
4. `source_msg_ids` 只能取输入行开头的 id，至少一个，拿不出证据的不要输出。

### 4.3 resolve

1. `entities` 只放人的称呼，尽量照抄 `known_contacts` 里的 alias。
2. 问的是主人自己（我 / 本人 / `owner_aliases` 里任何称呼）时**不要**放进 `entities`——
   否则 Spring 会去 `contact` 里找一个永远不存在的别名，然后反问用户「你说的是谁」。
3. `keywords` 放 2–4 个可检索的实词，不要「谁」「什么时候」「说了什么」这类空词。
4. `time_hint` 只在问题里有时间线索时填。

### 4.4 answer

1. 只用 `candidates` 里的材料，行格式 `类型:id|标签|内容`。
2. `answer` ≤ 120 字，每个结论都必须有对应的 `used_ids`（≤5 个）。
3. 材料不足 → `answer` 空串、`used_ids` 为 `[]`，**不许编造**。
4. 不许出现候选集里没有的人、时间、数字。

## 5. 离线体检

`QQMessageAssistantPython/qq_assistant_plugin/tests/test_memory_workflow_dsl.py` 会在每次跑 pytest 时检查：
应用模式与名称、四条支路共 15 条边的接线（多一条少一条都算错）、Start 变量名与 Spring 实际发送的键完全一致、
提示词里仍钉着 `[@我]` / `source_msg_ids` / 受控谓词表 / `used_ids` 规则、
四个代码节点能真的跑通（含脏输出、`used_ids` 不是数组、有结论却没引用三种兜底）、
各结束节点的输出变量名互不重复且恰好是那 8 个（重名会被 Dify 的「发布」检查清单挡下来）。

## 6. 导入之后还要做的事（人工）

1. 执行增量迁移 `QQMessageAssistantJava/src/main/resources/db/migrations/2026-10-30-phase7b-digest-window-fix.sql`
   （去掉 `chat_digest_item.uk_chat_period`、`source_msg_ids` 放宽到 TEXT、`message_log` 补 `(chat_id, created_at)` 索引）
   和 `2026-10-30-phase8-memory.sql`（建 `contact`、`memory_note`）。
2. 控制台导入 `design/QQAssistant-memory-workflow.yml`，确认依赖插件 `langgenius/tongyi` 已装、模型 `qwen3.8-flash` 可用。
   这次图换了，**必须重新导入并发布**，旧版本没有 `is_extract` / `is_resolve` 两个分岔。
3. 「访问 API」页拿 Key，配到后端环境变量：`DIFY_WORKFLOW_API_KEY`、`DIFY_WORKFLOW_BASE_URL`（形如 `http://<dify>/v1`）。
4. Dify 里各 Run 一次：
   - `task=digest` + 假消息 → `items` 是合法 JSON 且 `source_msg_ids` 指向真实 id；
   - `task=extract` + 六段格式的假消息 → `notes` / `contacts` 是合法 JSON；
   - `task=ask` + `stage=resolve` → 拿到 `entities`；再用 `stage=answer` + 手写 `candidates` 拿到 `answer` + `used_ids`。
5. 打开整理调度：`app_setting.digest_enabled = 1`，并在前端「群设置」里勾选要整理的群。
6. 打开事实归纳：`app_setting.memory_job_enabled = 1`（默认关）。第一次可调 `POST /api/memory/rebuild` 把历史消息全量补算一遍。

> 第 5、6 步没做完之前，整理和事实归纳都是**关着的**（两个开关默认 `0`），不会产生任何数据，也不会报错刷屏。

**时钟**：`message_log.created_at` 与 `chat_digest_item.created_at` 由 Java 写入本地时间（不用库里的
`CURRENT_TIMESTAMP`）——那台 MySQL 跑在 UTC，靠库默认值会和 `LocalDateTime.now()` 差 8 小时，
每小时的窗口永远选不中消息。新加的时间相关表请照这个规矩来。
