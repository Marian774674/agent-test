# QQ 消息智能助手 · 接口文档（API Contract）

> 本文是 Spring Boot 端 + LangBot 端的**对外接口契约**，供开发时对照实现。
> 组件职责见 [`architecture.md`](../../../../../design/architecture.md)；表结构见 [`database_schema.md`](../../../../../design/database_schema.md)；各接口的设计出处见 [`optimization_schemes.md`](../../../../../design/optimization_schemes.md)。
> 状态：**草案 → 定稿**。实现前如对某个字段有疑问，先改本文档再改代码。

---

## 一、通用约定

| 项 | 约定 |
|---|---|
| Base URL | `http://localhost:8080`（Spring Boot，端口见 `application.yaml`） |
| 统一响应体 | `{ "code": int, "message": string, "data": any }` |
| 成功 | `code = 0`，HTTP 200 |
| 业务/参数错误 | `code = 400`，HTTP 400，`message` 为可读原因 |
| 系统错误 | `code = 500`，HTTP 500 |
| 时间格式 | ISO-8601 本地时间，如 `2026-10-15T14:30:00`（`LocalDateTime` 默认序列化） |
| 枚举 | 字符串字面量，取值见各接口字段说明 |
| 鉴权 | 个人项目，暂不做（内网 localhost） |
| 分页 | 暂不做；列表接口返回全量（必要时用 `limit` 参数） |
| 幂等 | 见各接口说明；`check` 类接口可重复调用 |

**错误响应示例**
```json
{ "code": 400, "message": "草稿当前状态为 CONFIRMED，不可再处理", "data": null }
```

---

## 二、接口总览

### A. Spring Boot 对外 API（本项目 Phase 2 实现）

| 分组 | Method | Path | 调用方 | 幂等 | 用途 |
|---|---|---|---|---|---|
| 草稿 | POST | `/api/drafts` | Dify / LangBot | 否 | 创建草稿 |
| | GET | `/api/drafts` | Vue | 是 | 草稿列表 |
| | POST | `/api/drafts/{id}/confirm` | Vue | 否 | 确认草稿 |
| | POST | `/api/drafts/{id}/reject` | Vue | 否 | 拒绝草稿 |
| 日志 | POST | `/api/message-logs` | LangBot / 发送桥 | 否 | 批量写入消息日志 |
| | GET | `/api/message-logs` | Vue / 总结任务 | 是 | 按会话/方向查询日志 |
| 备忘 | POST | `/api/memos` | Dify | 否 | 创建备忘录 |
| | GET | `/api/memos` | Vue | 是 | 备忘录列表 |
| | GET | `/api/memos/{id}` | Vue | 是 | 备忘详情 |
| | GET | `/api/memos/{id}/reminders` | Vue | 是 | 提醒计划 |
| | PATCH | `/api/memos/{id}` | Vue / LangBot | 否 | 编辑 / 完成 / 取消 |
| | GET | `/api/memos/open` | LangBot（→Dify） | 是 | 未终结备忘摘要，供模型指认「改哪条」 |
| | GET | `/api/memos/{id}/history` | Vue | 是 | 变更记录（AI / 手动） |
| Agent 写入口 | POST | `/api/agent/ops` | Dify HTTP 节点 | **是（`eventId`）** | 备忘新建/修改/完成/取消 |
| 防重 | POST | `/api/collection-tasks/check` | Dify / LangBot | **是** | 查是否已回复 |
| | POST | `/api/collection-tasks/mark` | Dify / LangBot | 否 | 标记已回复 |
| | GET | `/api/collection-tasks/rules` | LangBot | 是 | 拉取前置拦截规则 |
| 拦截规则 | GET | `/api/intercept-rules` | Vue | 是 | 规则列表 |
| | POST | `/api/intercept-rules` | Vue | 否 | 新建规则 |
| | PUT | `/api/intercept-rules/{id}` | Vue | 否 | 修改规则 |
| | DELETE | `/api/intercept-rules/{id}` | Vue | 否 | 删除规则 |
| 画像·事实 | GET | `/api/profile/{senderId}` | Dify | 是 | 会话注入（含事实 + 画像） |
| | PUT | `/api/profile/facts` | Dify | 否 | 高置信事实写入（覆盖） |
| | GET | `/api/profile/facts` | Vue 总控台 | 是 | 事实列表 |
| | POST | `/api/profile/facts` | Vue 总控台 | 否 | 手动新增事实 |
| | PUT | `/api/profile/facts/{id}` | Vue 总控台 | 否 | 手动修改事实 |
| | DELETE | `/api/profile/facts/{id}` | Vue 总控台 | 否 | 手动删除事实 |
| 画像·风格 | GET | `/api/style-profile/{senderId}` | Vue | 是 | 查看当前画像 |
| | POST | `/api/style-profile/update` | Dify 总结工作流 | 否 | 写回画像 |
| 设置 | GET | `/api/settings` | Vue | 是 | 读取全部配置 |
| | PUT | `/api/settings` | Vue | 否 | 更新配置 |
| 文件任务 | GET | `/api/file-tasks` | Vue | 是 | 只读任务列表 |
| | POST | `/api/file-tasks/sync` | LangBot | 否 | 状态镜像同步 |
| 会话映射 | GET | `/api/conversations/resolve` | LangBot | **是** | 取当前 Dify conversation_id |
| | POST | `/api/conversations/bind` | LangBot | 否 | 绑定/覆盖 conversation_id |
| | POST | `/api/conversations/reset` | LangBot | **是** | 标记当前映射失效 |
| 认识的人 | GET | `/api/contacts` | Vue | 是 | 人物列表 |
| | POST | `/api/contacts` | Vue / `/api/ask` | 否 | 手工认人（`source=USER`） |
| | DELETE | `/api/contacts/{id}` | Vue | 否 | 删除一个称呼 |
| 历史查询 | POST | `/api/ask` | Vue | 否 | 一句话回查聊天记录、事实、备忘、文件 |
| 记忆归纳 | POST | `/api/memory/rebuild` | Vue / 运维 | 否 | 游标清零，全量补算事实 |
| 自动回复 | GET | `/api/chat-settings/reply-context` | LangBot | 是 | 开关 + 限流快照 + 子提示词 + 最近消息 |

### B. LangBot 对外 API（Phase 3 实现，供 Spring Boot 调用）

| Method | Path | 调用方 | 用途 |
|---|---|---|---|
| POST | `/api/langbot/send_message` | Spring Boot | 主动发文本 |
| POST | `/api/langbot/send_file` | Spring Boot | 主动发文件 |

---

## 三、草稿中心（`draft`）

### 3.1 `POST /api/drafts` — 创建草稿

**请求体**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `type` | string | 是 | `REPLY` \| `FILE` \| `MEMO` \| `FACT` |
| `senderId` | long | 是 | 归属用户 |
| `chatId` | long | 否 | 私聊/群 |
| `title` | string | 否 | 列表展示用标题 |
| `payload` | object | 是 | 结构随 `type` 变化，见下 |

**`payload` 按 type 的结构**

```jsonc
// REPLY —— 拟人回复草稿
// targetType 默认 "private"；targetId 缺省时按 chatId → senderId 回退
// content = 要发出去的话（草稿卡主句）；sourceContent = 对方说的那句（卡片顶部的引文），
// 免得确认时看不出在回谁。title 当摘要用，别把 content 再塞一遍——
// 前端会同时渲染 title 与 content，重复的内容在卡片上会出现两次。
{ "content": "好的，我看看", "sourceContent": "明天几点呀？",
  "targetType": "private", "targetId": 2002 }

// FILE —— 文件回传草稿
{ "taskRef": "ft_0001", "fileName": "表单.xlsx", "fileRef": "local://out/ft_0001.xlsx" }

// MEMO —— 备忘草稿（字段同 POST /api/memos）
{ "title": "班会", "content": "周四晚 7 点", "importance": 3, "category": "学业", "dueTime": "2026-10-17T19:00:00" }

// FACT —— 事实草稿
{ "factKey": "专业", "factValue": "计算机科学与技术", "confidence": 0.72 }
```

**响应** `data` = 草稿对象（含 DB 生成的 `id`、`status="PENDING"`）。

**示例**
```jsonc
// 请求
{ "type": "REPLY", "senderId": 1001, "chatId": 2002, "title": "回复草稿",
  "payload": { "content": "好的，我看看" } }

// 响应
{ "code": 0, "message": "ok",
  "data": { "id": 1, "type": "REPLY", "senderId": 1001, "chatId": 2002,
            "title": "回复草稿", "payload": { "content": "好的，我看看" },
            "status": "PENDING", "confirmedAt": null,
            "createdAt": "2026-10-15T14:30:00", "updatedAt": "2026-10-15T14:30:00" } }
```

### 3.2 `GET /api/drafts` — 草稿列表

**Query**

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `status` | string | 否 | `PENDING` \| `CONFIRMED` \| `REJECTED`；不传为全部 |
| `type` | string | 否 | `REPLY` \| `FILE` \| `MEMO` \| `FACT`；不传为全部 |

**响应** `data` = 草稿数组，按 `createdAt` 倒序。

### 3.3 `POST /api/drafts/{id}/confirm` — 确认草稿

**行为**：`PENDING → CONFIRMED`，置 `confirmedAt = now`。

**分发（确认后按 `type` 触发，Phase 2 后逐步接入）**

| type | 分发动作 | 接入阶段 |
|---|---|---|
| `REPLY` | 调 LangBot `send_message` 发送 | Plan 2-3（LangBot 接口就绪后） |
| `FILE` | 调 LangBot `send_file` 回传 | Plan 2-3 |
| `MEMO` | 写入 `memo` 表 | Plan 2-2 |
| `FACT` | 写入 `profile_fact` | Plan 2-3 |

**错误**：草稿不存在 → `400`；当前状态非 `PENDING` → `400`。

### 3.4 `POST /api/drafts/{id}/reject` — 拒绝草稿

**行为**：`PENDING → REJECTED`。错误同上。

---

## 四、消息日志（`message_log`）

### 4.1 `POST /api/message-logs` — 批量写入

**请求体**：`MessageLogItem` 数组（LangBot 每条消息进出时调用，异步、可批量）。

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `senderId` | long | 是 | 用户 |
| `chatId` | long | 否 | 会话 |
| `conversationId` | string | 否 | Dify conversation_id |
| `messageId` | string | 否 | 平台消息 id（桥侧发送成功时回报） |
| `dedupeKey` | string | 否 | 幂等键；相同值重复写入被跳过 |
| `direction` | string | 是 | `IN`（收到）\| `OUT`（发出） |
| `msgType` | string | 是 | `text` \| `image` \| `file` … |
| `content` | string | 否 | 文本内容 |

**响应** `data` = 实际写入条数（int；被 `dedupeKey` 挡掉的条目不计入）。

**示例**
```jsonc
// 请求
[ { "senderId": 1001, "chatId": 2002, "direction": "IN",  "msgType": "text", "content": "在吗" },
  { "senderId": 1001, "chatId": 2002, "direction": "OUT", "msgType": "text", "content": "在" } ]
// 响应
{ "code": 0, "message": "ok", "data": 2 }
```

### 4.2 `GET /api/message-logs` — 查询（Phase 3）

**Query**：`senderId`（可选）、`conversationId`（可选）、`direction`（可选，`IN|OUT`）、`limit`（可选，默认 100，最大 500）。

**响应** `data` = 日志数组，按 `id` 倒序。用于风格总结取原料与全链路排查。

---

## 五、备忘录（`memo`）

### 5.1 `POST /api/memos` — 创建备忘录

**请求体**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `senderId` | long | 是 | 用户 |
| `chatId` | long | 否 | 会话 |
| `title` | string | 是 | 标题 |
| `content` | string | 否 | 详情 |
| `importance` | int | 是 | 1–5，由 Dify 打分 |
| `category` | string | 否 | `学业`\|`社交`\|`健康`\|`财务`\|`日常`\|`其他` |
| `dueTime` | string | 否 | 明确时间；无则 null |
| `sourceMsgId` | long | 否 | 溯源源消息 |

**行为**：入库 `status=ACTIVE`，并按 `category`（**不是 importance**）依**提醒策略表**展开
`memo_reminder` 行。缴费和买牛奶的紧急程度不是一回事，一张按重要度的表必然对其中一个是错的。

| 类型 | 默认计划（末尾都带「到期时」） |
|---|---|
| 学业 | 前一天 20:00、提前 1 小时、到期时 |
| 财务 | 前一天 10:00、当天 08:00、到期时 |
| 社交 | 提前 2 小时、提前 30 分钟、到期时 |
| 健康 / 日常 | 提前 30 分钟、到期时 |
| 其他（含 `category` 为空） | 提前 1 小时、到期时 |

**到期时提醒是强制的**：只要 `dueTime` 还在未来、且 `importance > 1`，每条备忘都会排一条
标签 `截止` 的提醒（中文 `到期时`）。类型表只管「提前多久」，管不了「到点必须响一次」；
它固定占一个 `max-per-memo` 名额，名额不够时先砍离到期最远的提前档，绝不砍它。

- 计划**可配置**：`app.reminder.policy`（`application.yaml`），改完重启生效。
  写成**列表**、分类名放在 `category` 字段里，不要写成 `学业: [...]` 这种中文 key 的映射——
  Spring 属性名只认 ASCII，中文 key 会在绑定阶段被丢掉，YAML 改了会静默不生效：

  ```yaml
  app:
    reminder:
      max-per-memo: 3        # 一条备忘最多推几次（含到期那一条）
      dedupe-minutes: 20     # 两个点相差小于它就合并
      policy:
        - category: 学业
          points: ['-1d@20:00', '-1h']
        - category: 其他
          points: ['-1h']
  ```

  列表是整份覆盖：YAML 里写了 `policy` 就不再使用 Java 出厂表，没写的分类落到「其他」。
- 标签三种写法：`@HH:mm`（锚点当天此刻）、`±Nd@HH:mm`（锚点日期 ±N 天再取此刻）、
  `±N[dhm]`（相对锚点提前的天/小时/分钟）。这些标签原样存进 `memo_reminder.offset_label`。
- **锚点归一化**：`dueTime` 的时刻部分是 `00:00`（LLM 只给了日期）时，锚点取当天 **09:00**，
  否则纯日期备忘会算出凌晨 00:00 / 22:00 / 23:30 的提醒。
- 生成时依次：丢掉已过去的点 → 合并相差 `< dedupe-minutes`（默认 20）的点 →
  截断到 `max-per-memo - 1`（给到期那条留位，默认总共 3 条）→ 补「截止」。
  与「截止」相差 `< dedupe-minutes` 的提前档让位给「截止」，而不是反过来。
- 临到点才记的事不需要额外兜底：提前档全过去时，「截止」就是那条提醒
  （旧的 `立即` 标签不再生成，只保留中文翻译给历史数据用）。
- `importance = 1` 表示「纯信息、只存档」，不排任何提醒；其余级别不影响时间点。

**响应** `data` = 备忘对象（含 `id`、`status`）。

> `dueTime` 反序列化是**宽松**的：`2026-10-29T19:00:00`、`2026-10-29 19:00`、
> `2026-10-29T19:00:00+08:00`、`2026-10-29` 都能收。LLM 输出的时间格式不稳定，
> 不应因为一个空格就 500 丢掉整条备忘。

### 5.2 `GET /api/memos` — 备忘录列表

**Query**：`status`（可选，`ACTIVE`\|`REMINDED`\|`AWAITING_REPLY`\|`DONE`\|`CANCELLED`\|`OVERDUE`）、`senderId`（可选）。

### 5.3 `GET /api/memos/{id}` — 备忘详情

**响应** `data` = 备忘对象；不存在时 `code=400`。

### 5.4 `GET /api/memos/{id}/reminders` — 提醒计划

**响应** `data` = `MemoReminder[]`，按 `remindAt` 升序：

| 字段 | 说明 |
|---|---|
| `id` | 提醒 id |
| `offsetLabel` | 档位标签，如 `-1d@20:00`、`-1h`、`@08:00`、`截止`（到期时）；旧数据里可能还有 `24h`、`立即` 之类 |
| `offsetText` | 后端翻译好的中文，如「前一天 20:00」。前端直接显示，**不要**在前端维护映射表 |
| `remindAt` | 应该推送的时间 |
| `status` | `PENDING` \| `SENT` \| `CANCELLED` |
| `sentAt` | 实际推送时间 |

**说明**：改过 `category`/`importance`/`dueTime` 后旧档位保留为 `CANCELLED`（审计用）。
前端把 `CANCELLED` 项画成删除线展示。

### 5.4.1 `GET /api/memos/reminder-preview` — 提醒预览

**Query**：`category`（可选）、`dueTime`（可选，宽松格式）、`importance`（可选）。

编辑一条还没保存的备忘时，用它显示「将在这几个时刻提醒你」。响应 `data` =
`[{label, text, remindAt}]`，与真实排程走**同一份策略**，不会出现预览和实际不一致。
`dueTime` 为空返回 `[]`；解析不了返回 `code=400`。

### 5.4.2 `POST /api/memos/{id}/replan` — 重新排提醒

把该备忘未发的提醒置为 `CANCELLED`（不删除），再按当前策略重排。响应 `data` = 新的提醒计划。
用于把部署前按旧策略排好的提醒换成新策略；`DONE`/`CANCELLED` 的备忘返回 `code=400`。

### 5.5 `PATCH /api/memos/{id}` — 编辑 / 完成 / 取消

**请求体**（部分更新，传哪个改哪个）

| 字段 | 类型 | 说明 |
|---|---|---|
| `title` | string | 编辑标题 |
| `content` | string | 编辑详情 |
| `importance` | int | 改重要性 → **重算 `memo_reminder`** |
| `category` | string | 同上枚举，非法值返回 400 |
| `dueTime` | string | 改时间 → **重算 `memo_reminder`** |
| `status` | string | `DONE`（完成）\| `CANCELLED`（取消） |

**QQ 闭环**：用户在 QQ 回「完成/取消」时，LangBot 识别后也调本接口（LangBot 维护「当前激活提醒 `memo_id` → 用户」映射，才能路由到正确 memo）。

**行为**：改 `status` 为 `DONE`/`CANCELLED` 时，同步把该 memo 未发的 `memo_reminder` 置 `CANCELLED`。

---

### 5.6 `GET /api/memos/open` — 未终结备忘摘要

**Query**：`senderId`（必填）、`limit`（默认 20，clamp 1–50）

**响应** `data` = `MemoOpenItem[]`：

| 字段 | 说明 |
|---|---|
| `id` | 备忘 id —— **模型唯一可以引用的 id** |
| `title` | 标题 |
| `content` | 正文，截 60 字 |
| `dueTime` | 可空 |
| `status` | `ACTIVE` / `REMINDED` / `AWAITING_REPLY` / `OVERDUE` |
| `importance` | 1–5 |
| `category` | 分类 |

只含未终结状态（`DONE` / `CANCELLED` 不出现）；有 `dueTime` 的按时间升序在前，
没有的按 `updated_at` 倒序在后。插件把它 `json.dumps` 后作为 `open_memos` 喂给 Dify（见 `agent_contract.md` §1.4）。

---

### 5.7 `GET /api/memos/{id}/history` — 变更记录

**响应** `data` = `MemoChangeLog[]`，新的在前：

| 字段 | 说明 |
|---|---|
| `id` | 记录 id |
| `memoId` / `senderId` | 归属 |
| `source` | `AGENT`（Dify 调用）/ `VUE`（人工） |
| `op` | `CREATE` / `UPDATE` / `CLOSE` |
| `eventId` | 幂等键（人工改动为 null） |
| `beforeJson` / `afterJson` | 变更前后快照（整条 memo） |
| `resultJson` | 当时返回给 Dify 的确认信封（重放时直接返回它） |
| `createdAt` | 时间 |

前端只用它展示「时间 / 来源 / 改前改后摘要」；**它是备忘正文之外唯一的历史留存**，
所以提示词要求模型把 `content` 重写成「改动后的完整状态」，而不是在正文里堆历史。

---

### 5.8 `POST /api/agent/ops` — Agent 窄写入口（备忘）

Dify 的 `OP_HTTP` 节点直接调用本接口。**鉴权**：请求头 `X-Agent-Token` 必须等于
`app.agent.token`（环境变量 `AGENT_OPS_TOKEN`）；未配置时整组接口 503（fail closed），
配置了但头不对/缺失返回 401。

**请求体**：全部字段为字符串，空串 = 未提供（见 `agent_contract.md` §3.1）。

| 字段 | 说明 |
|---|---|
| `eventId` | 必填，幂等键 |
| `op` | `memo.create` / `memo.update` / `memo.close` |
| `senderId` | 必填；归属与幂等只认它 |
| `chatId` / `conversationId` / `query` | 审计用，可空 |
| `memoId` | `update`/`close` 用；`close` 为空时取该用户最新未终结备忘 |
| `title` / `content` / `importance` / `category` / `dueTime` / `clearDueTime` / `status` | 同 `MemoPatchRequest` 语义 |

**响应**：`Result<AgentOpsResult>`，`data` = `{op, memoId, status, message, replayed, reminders[]}`。

**错误语义（对 Dify 而言很关键）**：

| 情况 | HTTP | `code` |
|---|---|---|
| 成功 / 重放 | 200 | 0 |
| 业务拒绝（校验、归属、找不到、参数冲突） | **200** | **400** |
| token 缺失/错误 | 401 | 401 |
| 未配置 token | 503 | 503 |
| 未捕获异常 | 500 | 500 |

业务拒绝走 200 是为了让 Dify 的成功分支把 `message`（中文原因）转达给用户；
只有基础设施问题才走非 2xx，由 `OP_FAILED` 给通用道歉。

**幂等**：同一 `eventId` 只执行一次，第二次直接返回上次的 `result_json`（`replayed=true`）。

**归属**：`memoId` 不属于 `senderId` 时一律返回「备忘录不存在」，不区分“不存在”和“不是你的”。

---

## 六、统一回复防重（`collection_task`）

### 6.1 `POST /api/collection-tasks/check` — 查是否已回复（幂等）

**请求体**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `chatId` | long | 是 | 群/会话 |
| `taskType` | string | 是 | 任务类型，如 `学号收集` |
| `taskKey` | string | 是 | `chatId + taskType + 时间窗口` 归一化串 |

**响应**
```json
{ "code": 0, "message": "ok", "data": { "replied": false } }
```

### 6.2 `POST /api/collection-tasks/mark` — 标记已回复

**请求体**：同上三字段。**行为**：upsert 到 `UNIQUE(chat_id, task_key)`，置 `status=REPLIED`、`repliedAt=now`。

**响应** `data` = true。

### 6.3 `GET /api/collection-tasks/rules` — 拉取前置拦截规则

**调用方**：LangBot（启动 + 定时轮询，实现**热更新**）。
**响应** `data` = 启用中的拦截规则数组（按 `priority` 升序）：

```json
{ "code": 0, "message": "ok",
  "data": [ { "id": 1, "name": "学号", "keywords": "学号,姓名,专业",
              "regex": "(学号|姓名|专业)\\s*[:：]?\\s*", "replyTemplate": "学号 2023001，姓名 张三",
              "priority": 10 } ] }
```

> 命中后 LangBot **仍须**调 `check` 防重，再决定是否发送。

---

## 七、拦截规则管理（`intercept_rule`）

### 7.1 `GET /api/intercept-rules` — 列表
**响应** `data` = 全部规则（含 `enabled=false`）。

### 7.2 `POST /api/intercept-rules` — 新建
**请求体**：`{ name, keywords, regex, replyTemplate, priority, enabled }`（`name` 必填，其余可选）。

### 7.3 `PUT /api/intercept-rules/{id}` — 修改
**请求体**：同新建，部分更新。

### 7.4 `DELETE /api/intercept-rules/{id}` — 删除

---

## 八、画像：事实记忆（`profile_fact`）

### 8.1 `GET /api/profile/{senderId}` — 会话注入（Dify 用）

**响应** `data` = `{ facts: {...}, stylePrompt: "..." }`——Dify 在会话开始时注入。
```json
{ "code": 0, "message": "ok",
  "data": { "facts": { "学号": "2023001", "姓名": "张三", "专业": "计算机科学与技术" },
            "stylePrompt": "说话简短、常用‘嗯/好的’、偶尔发表情" } }
```

### 8.2 `PUT /api/profile/facts` — 高置信事实写入（Dify 用）

**请求体**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `senderId` | long | 是 | 主语归属为「本人」才高置信写 |
| `factKey` | string | 是 | `学号`\|`姓名`\|`专业`\|`目标`… |
| `factValue` | string | 是 | 值 |
| `confidence` | number | 否 | 0–1 |

**行为**：upsert 到 `UNIQUE(sender_id, fact_key)`（**改口直接覆盖**），`source=AUTO`。

### 8.3 事实增删改查（Vue 记忆总控台）

| Method | Path | 请求体 / 参数 | 说明 |
|---|---|---|---|
| GET | `/api/profile/facts` | `?senderId=` | 事实列表 |
| POST | `/api/profile/facts` | `{ senderId, factKey, factValue }` | 新增（`source=MANUAL`） |
| PUT | `/api/profile/facts/{id}` | `{ factKey?, factValue? }` | 修改 |
| DELETE | `/api/profile/facts/{id}` | — | **物理删除**（见 database_schema.md 说明） |

---

## 九、画像：风格（`style_profile`）

### 9.1 `GET /api/style-profile/{senderId}` — 查看当前画像
**响应** `data` = `{ senderId, profilePrompt, lastSummarizedAt }`。

### 9.2 `POST /api/style-profile/update` — 写回画像（Dify 总结工作流用）

**请求体**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `senderId` | long | 是 | 用户 |
| `profilePrompt` | string | 是 | 新的画像正文 |

**行为**：upsert 到 `UNIQUE(sender_id)`，置 `lastSummarizedAt=now`。
> 更新后由 Spring Boot 调 **Dify 应用配置 API** 把该画像写进用户画像 Prompt（Phase 4 确认调用方式）。

---

## 十、设置（`app_setting`）

### 10.1 `GET /api/settings` — 读取全部配置
**响应** `data` = kv 对象：
```json
{ "code": 0, "message": "ok",
  "data": { "summary_frequency": "DAILY_7D", "timezone": "Asia/Shanghai" } }
```
> `summary_frequency` 取值：`DAILY_7D`（每日 + 近 7 天）\| `DAILY_TODAY`（每日仅当日）\| `WEEKLY`（每周）。

### 10.2 `PUT /api/settings` — 更新配置
**请求体**：kv 对象（部分更新），如 `{ "summary_frequency": "WEEKLY" }`。

---

## 十一、文件任务镜像（`file_task`）

### 11.1 `GET /api/file-tasks` — 只读列表（Vue）
**Query**：`senderId`（可选）、`status`（可选）。
**响应** `data` = 任务数组：`{ id, taskRef, senderId, chatId, fileName, status, detail, createdAt, updatedAt }`。
> `status`：`PENDING`\|`PROCESSING`\|`NEED_INPUT`\|`AWAITING_CONFIRM`\|`SENT`\|`FAILED`\|`CANCELLED`。

### 11.2 `POST /api/file-tasks/sync` — 状态镜像同步（LangBot）
**请求体**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `taskRef` | string | 是 | LangBot 侧 taskId（幂等键） |
| `senderId` | long | 是 | 用户 |
| `chatId` | long | 否 | 会话 |
| `conversationId` | string | 否 | 触发任务的 Dify conversation_id |
| `fileName` | string | 否 | 文件名 |
| `request` | string | 否 | 用户原始填写要求 |
| `pendingRequest` | string | 否 | 等待文件时挂起的要求 |
| `status` | string | 是 | 见上枚举 |
| `missing` | string[] | 否 | Dify 判定仍缺的字段 |
| `confidence` | number | 否 | 填写置信度 0-1 |
| `needsConfirm` | boolean | 否 | 是否强制人工确认 |
| `fileRef` | string | 否 | 输入文件引用 `local://...` |
| `outputRef` | string | 否 | 产物文件引用 `local://...` |
| `sourceMessageId` | long | 否 | 源 QQ 消息 id |
| `idempotencyKey` | string | 否 | 重复投递去重 |
| `detail` | object | 否 | 状态详情（JSON 镜像） |

**行为**：按 `taskRef` upsert；缺省字段不覆盖已有值。**只写不回读业务逻辑**——状态机真身在 LangBot 插件内。
FILE 草稿确认发送成功后，Spring 直接把对应 `taskRef` 的 `status` 置为 `SENT`。

---

## 十二、会话映射（`conversation_session`）

> 用于把 QQ 会话与 Dify `conversation_id` 绑定，使 Dify 能跨消息看到上下文。
> 插件侧另有本地 JSON 兜底，Spring 是持久化真相源。契约详见 `agent_contract.md` §1.2 / §4。

### 12.1 `GET /api/conversations/resolve` — 解析当前会话

**Query**

| 参数 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `botUuid` | string | 是 | LangBot bot 标识（插件配置 `bot`） |
| `chatType` | string | 是 | `private` / `group` |
| `chatId` | long | 否 | 群号；私聊可省略（服务端归一为 0） |
| `senderId` | long | 是 | QQ 号 |

**响应** `data` = `{ "conversationId": "..." }`；没有 ACTIVE 映射时为 `null`。

### 12.2 `POST /api/conversations/bind` — 绑定 / 覆盖

**请求体**：`{ botUuid, chatType, chatId, senderId, conversationId }`。
**行为**：按 `(botUuid, chatType, chatId, senderId)` upsert，状态置 `ACTIVE` 并刷新 `lastActiveAt`。
**响应** `data` = 会话映射实体。

### 12.3 `POST /api/conversations/reset` — 标记失效

**请求体**：`{ botUuid, chatType, chatId, senderId }`。
**行为**：状态置 `RESET`；下次 `bind` 会覆盖为新的 `conversation_id`。不存在时幂等返回成功。

---

## 十三、LangBot 对外 API（Phase 3 实现）

> Spring Boot 是「提醒发起者」「草稿确认执行者」，但发 QQ 必须经 LangBot。以下接口由 **LangBot 暴露、Spring Boot 调用**。文本与文件是**两套契约，不混用**。

### 13.1 `POST /api/langbot/send_message` — 发文本

**请求体**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `target` | object | 是 | `{ "type": "private"\|"group", "id": 2002 }` |
| `content` | string | 是 | 文本 |
| `delayMs` | int | 否 | 拟人延迟（毫秒）；不传由 LangBot 按字数计算 |
| `bizRef` | string | 否 | 业务引用，如 `memo:123` / `draft:45`；LangBot 据此做 QQ 内闭环路由与幂等 |

### 13.2 `POST /api/langbot/send_file` — 发文件

**请求体**

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `target` | object | 是 | 同上 |
| `fileRef` | string | 是 | 文件引用（LangBot 本地路径或任务引用） |
| `fileName` | string | 否 | 展示文件名 |
| `bizRef` | string | 否 | 业务引用，如 `draft:45` |

**响应**（两者一致）：`{ "code": 0, "message": "ok", "data": { "messageId": "..." } }`。

**桥的实现约束**（Phase 3 定稿）：
- 发送**绝不自动重试**：宁可返回失败，也不能重复发消息。
- `bizRef` 相同的请求 24h 内幂等：直接返回上次的 `messageId`，不再发送。
- 同一 `bizRef` **同时在途**时，后到的请求返回 HTTP 409，绝不并发发两条；桥内的占位只在成功后才落盘。
- 幂等判断必须在文件存在性检查之前：产物文件被清理后再重试确认，仍应返回上次的 `messageId`。
- 文件通道回落 NapCat **只对 LangBot 的确定性拒绝（400/404/405）**；超时与 5xx 可能已送达，绝不回落，避免重复发文件。
- 拟人延迟只在 `private` 生效；`delayMs` 显式传入时以请求为准（定时提醒传 `0`，不排队等待）。
- 发送成功后由桥写 `OUT` 日志（带 `dedupeKey=out:<messageId>`）；日志失败不影响已成功的发送。
- 幂等状态文件默认落在 `send_bridge/data/sent.json` 的**绝对路径**，与启动目录无关。
- 插件自行发出的内容（`SEND` 文本、`FILE_FILL` 回传文件、`AWAITING_CONFIRM` 提示）由插件写 `OUT` 日志；插件直发文件不经过桥，**必须**自己补一条 `msgType=file` 的 `OUT` 记录，否则 `message_log` 只能看到文本。
- 业务/参数错误返回 HTTP 400 + `code: 400`；上游失败返回 HTTP 500 + `code: 500`。

> **实现说明（Phase 3 定稿）**：上面是本项目对 Spring Boot 的**稳定契约**，由 Python 侧 `send_bridge`（默认 `http://localhost:5301`）实现，不是 LangBot 原生路径。
> - `send_message` → 转发到 LangBot 官方 API `POST /api/v1/platform/bots/{bot_uuid}/send_message`（`X-API-Key` 鉴权，`target_type` 为 `person|group`，`message_chain=[{type:"Plain",text:...}]`），并在桥内完成拟人延迟与 OUT 日志。
> - `send_file` → 若 LangBot 官方 `send_message` 实测接受 `File` 组件则同样转发；否则由桥调 NapCat OneBot 11 的 `upload_private_file` / `upload_group_file`。
> 详见 `plans/2026-10-18-phase3-3-send-bridge.md`。

---

## 十四、记忆归纳与历史查询（Phase B）

> 三张新表见 `database_schema.md` §12/§13；Dify 那一侧的分支见 `memory_workflow.md`。
> 共同原则：**模型只做语义判断，认人、校验、落库全在 Spring**。

### 14.1 `GET /api/contacts` — 人物列表

**Query**：`senderId`（可选，按 QQ 号过滤；不传返回全部称呼）。

**响应** `data` = 数组：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | long | 主键 |
| `senderId` | long | QQ 号 |
| `alias` | string | 称呼（`(senderId, alias)` 唯一） |
| `relation` | string \| null | 同学 / 老师 / 室友…… |
| `importance` | int | 0 普通 / 1 一般 / 2 重要 |
| `note` | string \| null | 备注 |
| `source` | string | `AUTO`（夜间归纳写的）/ `USER`（用户认过的） |
| `updatedAt` | string | ISO 本地时间 |

### 14.2 `POST /api/contacts` — 手工认人

**请求体**：`{ senderId, alias, relation? }`。

**行为**：按 `(senderId, alias)` upsert，并把 `source` 强制写成 `USER`——**USER 行夜间任务永不覆盖**。
校验：`alias` 非空且 ≤64 字，`senderId` 必填；失败是 `code!=0` 的中文 message。

**响应** `data` = `contact` 实体。

### 14.3 `DELETE /api/contacts/{id}` — 删除一个称呼

**响应** `data` = `1`。删掉的只是「这个名字对应这个人」这一条，不影响消息与事实。

### 14.4 `POST /api/ask` — 一句话回查

**请求体**

```json
{
  "question": "阿黄什么时候考试",
  "clarify": { "alias": "阿黄", "senderId": 1753800374, "from": "室友" }
}
```

- `clarify` 可选：用户刚回答了「你说的阿黄是谁」时带上。Spring 先按 `USER` 写一条 contact，再拿这个映射直接作答。
- 空问题 → `code!=0`「问题不能为空」。

**响应** `data`

| 字段 | 类型 | 说明 |
|---|---|---|
| `answer` | string | 结论；降级、认不出人或找不到时为空串 |
| `degraded` | boolean | `true` = 作答失败，或模型给了结论但 `used_ids` 越界/为空；结论作废，只给证据 |
| `evidence` | array | `{ type, id, label, content, source?, quote? }`；`type ∈ msg/note/memo/file`，`id` 是纯数字，前端用 `type:id` 做 key |
| `needClarify` | object \| null | `{ alias, candidates: [{ senderId, alias, relation, hint }], occurrences }`；`candidates` 为空表示库里没这个称呼，需要用户补 QQ 号 |
| `scope` | object | 本次检索范围，见下；老前端可忽略 |

`evidence[]` 的 `source` / `quote`：

- `source`：出处，格式化字符串。群聊 `群名 · 人名 · MM-dd HH:mm`；私聊 `QQ 对方QQ · 人名 · MM-dd HH:mm`；
  无发送人名时退回 QQ 号。多条来源用 `；` 连接，最多 3 条，其余以「等 N 条」收尾（N 是**剩余**条数）。
- `quote`：第一条来源消息的原文，截 200 字。

`needClarify.occurrences`：该 alias 在 `message_log`（`direction='IN'`）正文里作为子串出现的行数，
跨全部会话，显示上限 999。未知 alias 才有值；同名多人的歧义分支为 `null`。
`occurrences=0` 时不会触发反问，直接按「没找到」返回。

`scope`：

```json
{
  "semantic": true,
  "lag": 0,
  "rounds": 2,
  "candidateCnt": 47,
  "channels": {
    "note": 3, "msg-person": 5, "msg-mention": 8, "msg-lexical": 12,
    "msg-semantic": 20, "memo": 0, "file": 0
  },
  "message": ""
}
```

| 字段 | 说明 |
|---|---|
| `semantic` | 语义通道是否可用 |
| `lag` | 尚未建立向量的消息条数 |
| `rounds` | 实际跑了几轮检索（1–3；认人反问的终态路径为 0） |
| `candidateCnt` | 去重、预算截断前的候选数（注意是 camelCase；DB 的 `ask_trace.candidate_cnt` 仍是下划线列名） |
| `channels` | **键固定为 7 个，永远出现**：`note / msg-person / msg-mention / msg-lexical / msg-semantic / memo / file`。值为 `null` = 该路没跑、不可用或报错；`0` = 跑了但没有命中。两者不能混 |
| `message` | 给人看的一句话，前端原样显示；空串表示无需解释。**即使 `answer` 非空也可能非空**（如「未登记的联系人，按名字检索」） |

计数口径：每路原始命中、按查询串累加、去重前，跨轮累加；所以各通道之和可以大于 `candidateCnt`。

**流程**（完整链路与终态表见 `intelligent_retrieval.md` §7）：

1. Dify `stage=resolve` 抽 `entities` / `keywords` / `queries` / `time_hint` / `time_from` / `time_to`；
   Spring 额外传 `current_time`（ISO 本地时间，秒精度），供 Dify 换算相对时间；
2. Spring 用 `contact.alias` 精确认人：≥2 个不同 QQ → 立即 `needClarify`（带候选，**不检索**）；
   0 条 → 记为未知 alias、统计 `occurrences` 后照常检索；1 条 → 认下；
3. 检索前同步补跑向量（最多 `app.retrieval.pre-run-index-limit`，默认 500 条），剩余写入 `scope.lag`；
4. 七路召回（全部不带会话过滤）→ RRF 融合去重 → 时间软分段 → 按 30000 字符 / 150 条预算截断；
5. Dify `stage=answer` 在候选集内作答并给出 `used_ids`；Spring 校验 `used_ids ⊆ 候选集`；
6. 答不出（空答案 / 作答失败 / 证据越界）且未到第 3 轮 → Dify `stage=expand` 要新检索串/实体后重查，
   候选集**跨轮累计**；最多 3 轮（含第 1 轮）；
7. 循环结束后按终态表返回结论、证据、`needClarify` 或 `scope.message`。

**不做什么**：不做 QQ 端提问（检索只服务前端 `/api/ask`）；不做会话范围筛选、结果分页；
不做 ANN 索引（全表暴力余弦，50 万条以上再说）；不检索文件正文；不用 Dify Dataset。
多轮追问最多 3 轮（含第 1 轮），不是无限追问。

### 14.5 `POST /api/memory/rebuild` — 全量补算

**行为**：把 `app_setting.memory_job_cursor` 置 `0`，然后循环跑夜间归纳，直到游标不再前进为止（最多 50 批，每批 200 条 `IN` 消息）。

**响应** `data` = 本次累计写入的事实条数。

**注意**：幂等靠 `(subject, predicate, value)` 去重，重复补算不会产生重复行；但**不会删除**已经写进去的旧事实。
这是运维/首次导入用的口子，不是日常开关——日常靠 `app_setting.memory_job_enabled=1` + 每天 00:10 的定时任务。

### 14.5.1 `POST /api/memory/embeddings/rebuild` — 重建语义向量索引

**请求体**：无（当前模型由 `app.embedding.model-id` 决定）。

**行为**：删除该模型的全部 `message_embedding` 行，按批重算整张 `message_log`，
最后重新加载进程内索引。**仅手动触发**，不在夜间自动跑。

**响应** `data` = 本次重新写入的向量行数。向量服务不可用时返回 `0`，且**不删除**已有数据。

### 14.5.2 `GET /api/memory/embeddings/status` — 语义索引状态

**响应** `data`

```json
{ "semantic": true, "indexed": 12345, "lag": 0 }
```

| 字段 | 说明 |
|---|---|
| `semantic` | 语义通道是否可用（向量服务就绪且索引加载成功） |
| `indexed` | 当前进程内索引的向量条数 |
| `lag` | 尚缺向量的 `IN` 消息条数 |

### 14.6 `GET /api/chat-settings/reply-context` — 自动回复的决策快照（LangBot）

**用途**：插件在调 Dify **之前**取一次，一次拿齐「这个会话能不能自动回复、还能说几条、
主人写的人设、群里刚才在聊什么」。分三次请求会让快照之间互相不一致。

**请求参数**

| 参数 | 必填 | 默认 | 说明 |
|---|---|---|---|
| `chatId` | 是 | — | 群号；私聊填对方 QQ 号 |
| `kind` | 否 | `GROUP` | `GROUP` / `PRIVATE` |
| `limit` | 否 | `15` | 最近消息条数，clamp 1–50 |

**响应** `data`

```json
{
  "enabled": true,
  "replyPrompt": "我是张三，说话随意一点，别提我工作",
  "rateLimit": 20,
  "outLastHour": 3,
  "recentMessages": [
    {"senderName": "阿黄", "direction": "IN", "content": "明天几点集合", "createdAt": "2026-10-30T21:14:00"},
    {"senderName": "", "direction": "OUT", "content": "八点半东门", "createdAt": "2026-10-30T21:15:00"}
  ]
}
```

| 字段 | 语义 |
|---|---|
| `enabled` | `app_setting.reply_enabled`（总开关，缺省 `true`）**且** `chat_setting.auto_reply_enabled`；没建开关行 = `false`（不报错） |
| `replyPrompt` | `app_setting.reply_prompt`；空串 = 主人没写 |
| `rateLimit` | `app_setting.reply_rate_limit`，默认 **20**；非法值也回落默认 |
| `outLastHour` | 该会话最近一小时 `direction='OUT' AND reply_kind='CHAT'` 的行数 |
| `recentMessages` | 该会话最近 `limit` 条（两个方向都要），**按时间正序**；`content` 压掉换行、截 200 字 |

**总开关（`app_setting.reply_enabled`）**：一键闭嘴。只有明确的
`false` / `0` / `no` / `off` 才算关，**没配过一律当开**——它是紧急闸，
不能因为升级或新装把用户逐个打开的会话顺手关掉。它只影响「替你说日常话」，
备忘、查资料、文件填写照常（那些走 `TASK`）。

**为什么计 `reply_kind='CHAT'` 而不是所有 OUT**：限流只该约束「替主人闲聊」。
把拦截回复、备忘确认语算进来，会让一条日更通知把当天的自动回复额度全吃掉。

**索引**：`message_log` 的 `idx_chat_created (chat_id, created_at)` 同时覆盖最近消息与这一小时的 `COUNT`。

**私聊的会话键是对方 QQ，不是 `message_log.chat_id`**：私聊没有群号，插件写日志时
`chat_id` 一律为 `NULL`，`sender_id` 才是对方。所以 `kind=PRIVATE` 时，`outLastHour`
与 `recentMessages` 都按 `chat_id IS NULL AND sender_id = chatId` 过滤——
和 `chat_setting.chat_id`（私聊行存对方 QQ）用的是同一个值。
两种会话必须分开查：群号可能恰好等于某个人的 QQ，混着查会把两个会话串起来。

---

## 十五、待 Phase 4 确认的集成点

- **Dify 应用配置 API**：`POST /api/style-profile/update` 后更新画像 Prompt 的调用方式、鉴权、字段名。
- **NapCat 文件收发 API**：Phase 3 随 `send_file` 一并确认。
- **Dify 侧 Tool 定义**：Dify 调上述 Spring Boot API 的 Tool 参数 schema，Phase 4 编写。
