# QQ 消息智能助手 · 数据库表结构设计（Phase 1）

> 本文是 Phase 1「整体架构与数据库表结构设计」的产出，把 [`architecture.md`](./architecture.md) 的三个贯穿抽象与 [`optimization_schemes.md`](./optimization_schemes.md) 五案所需的持久化落成具体表结构。
> **技术底座：MySQL 8 + MyBatis-Plus。**
> 范围：表结构 + 可执行 DDL。MyBatis-Plus 实体/映射、REST 接口实现、迁移工具接入留给 Phase 2。

---

## 一、通用约定

| 项 | 约定 |
|---|---|
| 引擎 / 字符集 | `InnoDB` / `utf8mb4`（`utf8mb4_0900_ai_ci`） |
| 主键 | `id BIGINT UNSIGNED AUTO_INCREMENT` |
| 审计字段 | `created_at`、`updated_at`（`DATETIME`，由数据库默认值自动维护） |
| 逻辑删除 | `is_deleted TINYINT(1) DEFAULT 0`（**仅** `draft` / `memo`） |
| 枚举落库 | `VARCHAR`，值即语义（如 `PENDING`） |
| JSON 字段 | MySQL 8 原生 `JSON` 类型 |
| 命名 | 表名小写下划线、字段小写下划线；布尔用 `TINYINT(1)` |

**关于逻辑删除的取舍**：`profile_fact` / `style_profile` **不使用** `is_deleted`。因为两者都有「唯一键」约束（`UNIQUE(sender_id, fact_key)` / `UNIQUE(sender_id)`），逻辑删除后同键无法再插入，会与「改口直接覆盖」「总控台增删改」冲突。这两张表是小数据，删除即物理删除、更新即覆盖。

---

## 二、表清单与来源

| # | 表 | 用途 | 来源 |
|---|---|---|---|
| 1 | `draft` | 统一草稿中心（REPLY/FILE/MEMO/FACT） | 抽象①，方案①②③⑤ |
| 2 | `message_log` | 统一消息日志（风格总结原料 + 全链路排查） | 抽象②，方案⑤ |
| 3 | `memo` | 备忘录主体 | 方案③ |
| 4 | `memo_reminder` | 提醒计划（按 category 展开为多行） | 方案③ |
| 5 | `collection_task` | 统一回复防重状态 | 方案② |
| 6 | `intercept_rule` | 前置拦截规则（关键词 + 正则，热更新） | 方案② |
| 7 | `profile_fact` | 事实记忆（学号/姓名… 逐条 kv） | 方案⑤ |
| 8 | `style_profile` | 风格画像（注入 Dify 的画像正文） | 方案⑤ |
| 9 | `app_setting` | 全局配置（总结频率 / 窗口等） | 方案⑤ |
| 10 | `file_task` | 文件任务状态镜像（Vue 只读） | 方案④ |
| 11 | `conversation_session` | QQ 会话 ↔ Dify `conversation_id` 映射 | Dify-Centric 整改（`design/agent_contract.md`） |
| 12 | `memo_change_log` | 备忘变更留痕 + Agent 写操作幂等台账 | `design/agent_contract.md` §3 |
| 13 | `contact` | 认识的人：称呼 ↔ QQ 号的映射（认人 / 反问闭环） | F4 历史查询，`core_features.md` §7 |
| 14 | `memory_note` | 夜间事实归纳（受控谓词 + 证据 id） | F4 历史查询，`core_features.md` §7 |
| 15 | `message_embedding` | 消息向量（语义召回，只索引 `IN`） | `intelligent_retrieval.md` §3.1 |
| 16 | `ask_trace` | 一次问答的检索轨迹（问题/通道/计数，不存正文） | `intelligent_retrieval.md` §3.3 |

---

## 三、DDL

### 1. `draft` — 统一草稿中心

```sql
CREATE TABLE `draft` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `type`         VARCHAR(16)     NOT NULL COMMENT 'REPLY|FILE|MEMO|FACT',
  `sender_id`    BIGINT UNSIGNED NOT NULL,
  `chat_id`      BIGINT UNSIGNED NULL,
  `title`        VARCHAR(255)    NULL COMMENT '列表展示用简短标题',
  `payload`      JSON            NOT NULL COMMENT '各 type 自定结构',
  `status`       VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING|CONFIRMED|REJECTED',
  `confirmed_at` DATETIME        NULL,
  `created_at`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `is_deleted`   TINYINT(1)      NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_status_created` (`status`, `created_at`),
  KEY `idx_type_status` (`type`, `status`),
  KEY `idx_sender` (`sender_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='统一草稿中心';
```

> `payload` 不设固定 schema（Phase 2 按 `type` 定）：`REPLY`→回复正文；`FILE`→文件引用/回传信息；`MEMO`→备忘字段；`FACT`→事实 kv。超时保持 `PENDING`，不自动过期。

### 2. `message_log` — 统一消息日志

```sql
CREATE TABLE `message_log` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `sender_id`  BIGINT UNSIGNED NOT NULL,
  `sender_name` VARCHAR(64)    NULL COMMENT '发送者昵称',
  `chat_id`    BIGINT UNSIGNED NULL,
  `chat_name`  VARCHAR(128)    NULL COMMENT '群名；私聊为 NULL',
  `conversation_id` VARCHAR(64) NULL COMMENT 'Dify conversation_id',
  `message_id` VARCHAR(64)     NULL COMMENT '平台消息 id',
  `dedupe_key` VARCHAR(128)    NULL COMMENT '幂等键，重复写入被忽略',
  `direction`  VARCHAR(8)      NOT NULL COMMENT 'IN|OUT',
  `reply_kind` VARCHAR(8)      NULL COMMENT 'OUT 专用：TASK|CHAT',
  `msg_type`   VARCHAR(16)     NOT NULL COMMENT 'text|image|file...',
  `content`    TEXT            NULL,
  `created_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dedupe` (`dedupe_key`),
  KEY `idx_sender_created` (`sender_id`, `created_at`),
  KEY `idx_created` (`created_at`),
  KEY `idx_conversation` (`conversation_id`),
  KEY `idx_chat_created` (`chat_id`, `created_at`),
  FULLTEXT KEY `ft_content` (`content`) WITH PARSER ngram
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='统一消息日志';
```

> 只增不删，故无 `is_deleted`/`updated_at`。写入方为 LangBot（异步、可批量）。
> `dedupe_key` 走唯一索引：批内/重试重复的日志被跳过，不会写入两条；`NULL` 不参与唯一性判断。
> `ft_content` 是中文全文召回（`msg-mention` / `msg-lexical`）的依据，使用 MySQL 8 自带的
> `ngram` 分词（`ngram_token_size` 默认 2，单字查询匹配不到）。迁移见
> `db/migrations/2026-10-31-embedding-retrieval.sql`。

### 3. `memo` — 备忘录主体

```sql
CREATE TABLE `memo` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `sender_id`     BIGINT UNSIGNED NOT NULL,
  `chat_id`       BIGINT UNSIGNED NULL,
  `title`         VARCHAR(255)    NOT NULL,
  `content`       TEXT            NULL,
  `importance`    TINYINT UNSIGNED NOT NULL COMMENT '1-5',
  `category`      VARCHAR(32)     NULL COMMENT '学业|社交|健康|财务|日常|其他',
  `due_time`      DATETIME        NULL COMMENT '明确时间，无则 NULL',
  `status`        VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE'
                  COMMENT 'ACTIVE|REMINDED|AWAITING_REPLY|DONE|CANCELLED|OVERDUE',
  `source_msg_id` BIGINT UNSIGNED NULL COMMENT '溯源源消息',
  `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `is_deleted`    TINYINT(1)      NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_sender_status` (`sender_id`, `status`),
  KEY `idx_status_due` (`status`, `due_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='备忘录';
```

### 4. `memo_reminder` — 提醒计划

```sql
CREATE TABLE `memo_reminder` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `memo_id`      BIGINT UNSIGNED NOT NULL,
  `offset_label` VARCHAR(16)     NOT NULL COMMENT '-1d@20:00|-1h|@08:00|立即（旧数据可能是 24h 等）',
  `remind_at`    DATETIME        NOT NULL,
  `status`       VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING|SENT|CANCELLED',
  `sent_at`      DATETIME        NULL,
  `created_at`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_status_remind` (`status`, `remind_at`),
  KEY `idx_memo` (`memo_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='提醒计划';
```

> 创建 `memo` 时按其 `category` 依策略表**展开**成多行（如 学业 → 前一天 20:00 + 提前 1 小时两行，见 `api_contract.md` §5.1）；改 `category`/`importance`/`dueTime` 或取消 memo 时同步本表。实际调度是 Spring `@Scheduled`（不是 Quartz），按 `status='PENDING' AND remind_at <= NOW()` 扫描。
> **不建外键**（与全项目一致），`memo_id` 由应用层维护。

### 4.1 `memo_change_log` — 备忘变更留痕 + Agent 幂等台账

```sql
CREATE TABLE IF NOT EXISTS `memo_change_log` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `memo_id`         BIGINT UNSIGNED NOT NULL,
  `sender_id`       BIGINT UNSIGNED NOT NULL,
  `source`          VARCHAR(16)     NOT NULL COMMENT 'AGENT|VUE',
  `op`              VARCHAR(16)     NOT NULL COMMENT 'CREATE|UPDATE|CLOSE',
  `event_id`        VARCHAR(64)     NULL COMMENT '幂等键；人工改动为 NULL',
  `before_json`     TEXT            NULL,
  `after_json`      TEXT            NULL,
  `result_json`     TEXT            NULL,
  `conversation_id` VARCHAR(64)     NULL,
  `created_at`      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_event` (`event_id`),
  KEY `idx_memo` (`memo_id`),
  KEY `idx_sender_created` (`sender_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='备忘变更留痕 + Agent 幂等台账';
```

> 一行承担两件事：
> 1. **审计**：前端「变更记录」读它，因为新路径下正文会被重写成「改动后的完整状态」，旧值只能留在这里。
> 2. **幂等**：Dify 的写操作带 `event_id`，服务端在同一事务里先 `INSERT IGNORE` 占位，
>    影响行数为 0 就是重放，直接返回 `result_json` 里存的上次结果（`replayed=true`）。
>    `event_id` 可空 + 唯一索引：MySQL 允许多个 NULL，因此 Vue 手改的行不干扰去重。
>
> 它**不是** `message_log` 的替代品：`message_log` 记「说了什么」，本表记「改了什么」。
> 业务写入不进 `message_log`（否则风格总结会把“改好了：班会”当成用户语气样本）。

### 5. `collection_task` — 统一回复防重状态

```sql
CREATE TABLE `collection_task` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `chat_id`      BIGINT UNSIGNED NOT NULL,
  `task_type`    VARCHAR(32)     NOT NULL,
  `task_key`     VARCHAR(128)    NOT NULL COMMENT 'chat_id+task_type+时间窗口 归一化串',
  `window_start` DATETIME        NULL,
  `status`       VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING|REPLIED',
  `replied_at`   DATETIME        NULL,
  `created_at`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_chat_task` (`chat_id`, `task_key`),
  KEY `idx_chat_status` (`chat_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='统一回复防重状态';
```

> `check` / `mark` 幂等落在唯一键 `uk_chat_task` 上：命中即「已回复」，未命中即新批次。

### 6. `intercept_rule` — 前置拦截规则

```sql
CREATE TABLE `intercept_rule` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `name`           VARCHAR(64)     NOT NULL,
  `keywords`       VARCHAR(512)    NULL COMMENT '逗号分隔，先关键词快速匹配',
  `regex`          VARCHAR(512)    NULL COMMENT '再正则捕获',
  `reply_template` TEXT            NULL COMMENT '命中后回复内容',
  `priority`       INT             NOT NULL DEFAULT 0,
  `enabled`        TINYINT(1)      NOT NULL DEFAULT 1,
  `created_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_enabled_priority` (`enabled`, `priority`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='前置拦截规则';
```

> LangBot 启动/定时拉取本表实现**热更新**；改规则不改代码、不重启。

### 7. `profile_fact` — 事实记忆

```sql
CREATE TABLE `profile_fact` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `sender_id`  BIGINT UNSIGNED NOT NULL,
  `fact_key`   VARCHAR(64)     NOT NULL COMMENT '学号|姓名|专业|目标...',
  `fact_value` VARCHAR(512)    NOT NULL,
  `confidence` DECIMAL(3,2)    NULL,
  `source`     VARCHAR(16)     NOT NULL DEFAULT 'AUTO' COMMENT 'AUTO|MANUAL',
  `created_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sender_key` (`sender_id`, `fact_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='事实记忆';
```

> 改口**直接覆盖**（upsert 到 `uk_sender_key`）；总控台手动增删改 = `source='MANUAL'`。无 `is_deleted`（见「通用约定」）。

### 8. `style_profile` — 风格画像

```sql
CREATE TABLE `style_profile` (
  `id`                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `sender_id`          BIGINT UNSIGNED NOT NULL,
  `profile_prompt`     TEXT            NOT NULL COMMENT '注入 Dify 的画像正文',
  `last_summarized_at` DATETIME        NULL,
  `created_at`         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sender` (`sender_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='风格画像';
```

> 一人一行，定期总结 / 手动编辑均 upsert 覆盖。

### 9. `app_setting` — 全局配置

```sql
CREATE TABLE `app_setting` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `setting_key`   VARCHAR(64)     NOT NULL COMMENT '如 summary_frequency',
  `setting_value` VARCHAR(512)    NULL,
  `description`   VARCHAR(255)    NULL,
  `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_setting_key` (`setting_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='全局配置';
```

### 10. `file_task` — 文件任务状态镜像

```sql
CREATE TABLE `file_task` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `task_ref`   VARCHAR(64)     NOT NULL COMMENT 'LangBot 侧 taskId',
  `sender_id`  BIGINT UNSIGNED NOT NULL,
  `chat_id`    BIGINT UNSIGNED NULL,
  `conversation_id` VARCHAR(64) NULL COMMENT '触发任务的 Dify conversation_id',
  `file_name`  VARCHAR(255)    NULL,
  `request`    TEXT            NULL COMMENT '用户原始填写要求',
  `pending_request` TEXT       NULL COMMENT '等待文件时挂起的要求',
  `status`     VARCHAR(20)     NOT NULL DEFAULT 'PENDING'
               COMMENT 'PENDING|PROCESSING|NEED_INPUT|AWAITING_CONFIRM|SENT|FAILED|CANCELLED',
  `missing`    JSON            NULL COMMENT 'Dify 判定仍缺的字段',
  `confidence` DECIMAL(3,2)    NULL COMMENT '填写置信度 0-1',
  `needs_confirm` TINYINT(1)   NULL COMMENT '是否强制人工确认',
  `file_ref`   VARCHAR(512)    NULL COMMENT '输入文件引用 local://...',
  `output_ref` VARCHAR(512)    NULL COMMENT '产物文件引用 local://...',
  `source_message_id` BIGINT UNSIGNED NULL COMMENT '源 QQ 消息 id',
  `idempotency_key` VARCHAR(128) NULL COMMENT '重复投递去重',
  `detail`     JSON            NULL COMMENT '镜像 LangBot 状态详情',
  `created_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_ref` (`task_ref`),
  KEY `idx_sender_status` (`sender_id`, `status`),
  KEY `idx_conversation` (`conversation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='文件任务状态镜像';
```

> **状态机真身在 LangBot 插件内**（内存 + JSON 落盘）；本表仅为 Vue 只读的镜像——LangBot 状态变更时顺带同步一行（只写不回读业务逻辑）。
> Phase 3 起，FILE 草稿确认发送成功会由 Spring 把 `status` 推进到 `SENT`；`output_ref` 就是草稿 `fileRef` 指向的产物。

### 11. `conversation_session` — QQ 会话 ↔ Dify conversation_id

```sql
CREATE TABLE `conversation_session` (
  `id`                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `bot_uuid`             VARCHAR(64)     NOT NULL,
  `chat_type`            VARCHAR(16)     NOT NULL COMMENT 'private|group',
  `chat_id`              BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '私聊为0，群聊为群号',
  `sender_id`            BIGINT UNSIGNED NOT NULL,
  `dify_conversation_id` VARCHAR(64)     NOT NULL,
  `status`               VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE|RESET',
  `last_active_at`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `created_at`           DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`           DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_session` (`bot_uuid`, `chat_type`, `chat_id`, `sender_id`),
  KEY `idx_sender` (`sender_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='QQ会话与Dify conversation_id映射';
```

> 私聊 `chat_id` 归一为 `0`，让唯一键对私聊同样生效；群聊按 `(群号, 成员)` 隔离。
> `RESET` 只表示当前映射失效：`resolve` 只返回 `ACTIVE`，下次 `bind` 会覆盖为新的 `conversation_id`。

---

### 12. `contact` — 认识的人

```sql
CREATE TABLE `contact` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `sender_id` BIGINT UNSIGNED NOT NULL,
  `alias` VARCHAR(64) NOT NULL COMMENT '用户/模型认出来的称呼',
  `relation` VARCHAR(64) NULL COMMENT '同学|老师|室友…',
  `importance` TINYINT NOT NULL DEFAULT 0 COMMENT '0=普通 1=一般 2=重要',
  `note` VARCHAR(255) NULL,
  `source` VARCHAR(8) NOT NULL DEFAULT 'AUTO' COMMENT 'AUTO|USER',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sender_alias` (`sender_id`, `alias`),
  KEY `idx_alias` (`alias`),
  KEY `idx_sender` (`sender_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='认识的人';
```

> **`source` 的规则**：夜间归纳只写 `AUTO` 行，并且**永不覆盖 `USER` 行**；用户在 Vue 或 `/api/ask` 里认过人之后，
> 这个称呼就固定下来。同一称呼可以挂在多个 QQ 号上（同名不同人），这正是 `/api/ask` 反问答「是哪一个」的依据。

### 13. `memory_note` — 事实归纳

```sql
CREATE TABLE `memory_note` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `subject_sender_id` BIGINT UNSIGNED NULL COMMENT '主体；空=关于主人自己',
  `predicate` VARCHAR(64) NOT NULL COMMENT '受控词表',
  `value` VARCHAR(255) NOT NULL,
  `happened_at` DATETIME NULL,
  `source_msg_ids` VARCHAR(512) NOT NULL COMMENT '逗号分隔的 message_log.id',
  `confidence` DECIMAL(3,2) NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_subject_pred` (`subject_sender_id`, `predicate`),
  KEY `idx_predicate` (`predicate`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='事实归纳';
```

> **为什么没有 `UNIQUE(subject, predicate, value)`**：`subject_sender_id` 可以是 `NULL`（关于主人自己），
> MySQL 唯一索引对 NULL 不去重，加了也拦不住重复；去重放在 Spring 里做（批次内 + 与库内已有行比对）。
> **`predicate` 是受控词表**：生日 / 姓名 / 专业 / 班级 / 学号 / 住址 / 电话 / 时间安排 / 偏好 / 关系 / 其他，
> 词表外的值落 `其他`。
> **`source_msg_ids` 必须落在本批次内**，否则整条丢弃——没有原文支撑的「事实」比没有事实更糟。

### 14. `message_embedding` — 消息向量

```sql
CREATE TABLE `message_embedding` (
  `message_log_id` BIGINT UNSIGNED NOT NULL,
  `model`          VARCHAR(64) NOT NULL COMMENT '模型标识，换模型=全量重建',
  `dim`            SMALLINT NOT NULL,
  `vector`         MEDIUMBLOB NOT NULL COMMENT 'float32 小端，长度 = dim*4',
  `created_at`     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`message_log_id`,`model`),
  KEY `idx_model` (`model`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消息向量';
```

> 只索引 `direction='IN'` 且正文非空的消息（机器人发出的 `OUT` 不是证据）。
> 768 维以内都在 3KB/条以内，10 万条约 200MB；查询时全量读入进程内 off-heap 索引做暴力余弦。
> 主键带 `model`：换模型即全量重建，旧模型的向量不会与新模型混用。

### 15. `ask_trace` — 一次问答的检索轨迹

```sql
CREATE TABLE `ask_trace` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `question`       VARCHAR(512)  NOT NULL,
  `entities`       VARCHAR(512)  NULL,
  `queries`        VARCHAR(1024) NULL,
  `channels`       VARCHAR(128)  NULL COMMENT '命中非空的召回通道，逗号分隔',
  `candidate_cnt`  INT NOT NULL DEFAULT 0,
  `rounds`         TINYINT NOT NULL DEFAULT 1,
  `answer`         VARCHAR(1024) NULL,
  `degraded`       TINYINT NOT NULL DEFAULT 0,
  `latency_ms`     INT NULL,
  `created_at`     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='一次问答的检索轨迹';
```

> 只存问题、实体/查询串、各通道计数与是否降级，**不存原文正文**。
> 用途：漏答时能回答「当时查了哪几路、各命中几条、向量索引落后多少」，而不是靠猜。
> 注意列名 `candidate_cnt` 是数据库列（下划线）；`/api/ask` 响应里的同义字段是
> `scope.candidateCnt`（camelCase）。

---

## 四、关系与索引 ↔ 查询场景

```
memo 1 ── N memo_reminder
memo 1 ── N memo_change_log
contact.sender_id ─ ─ memory_note.subject_sender_id   （逻辑关联，不建外键）
memory_note.source_msg_ids ─ ─ message_log.id         （逗号分隔，应用层校验）
（其余表相互独立；表间不建外键，跨表语义由应用层维护）
```

| 查询场景 | 接口/触发 | 命中索引 |
|---|---|---|
| 草稿列表（待处理） | `GET /api/drafts?status=PENDING` | `draft.idx_status_created` |
| 草稿列表（按类型） | Vue 草稿中心分组 | `draft.idx_type_status` |
| 风格总结按时间窗口拉日志 | 定时任务 | `message_log.idx_created` / `idx_sender_created` |
| 到期提醒扫描 | Quartz | `memo_reminder.idx_status_remind` |
| Agent 写操作去重 | `POST /api/agent/ops` | `memo_change_log.uk_event` |
| 备忘变更记录（前端 + 审计） | `GET /api/memos/{id}/history` | `memo_change_log.idx_memo` |
| 未终结备忘摘要（喂 Dify） | `GET /api/memos/open` | `memo.idx_sender_status` |
| 防重 check/mark | `POST /api/collection-tasks/*` | `collection_task.uk_chat_task` |
| 拦截规则拉取 | LangBot 定时 | `intercept_rule.idx_enabled_priority` |
| 会话注入事实记忆 | `GET /api/profile/{sender_id}` | `profile_fact.uk_sender_key`（前缀 `sender_id`） |
| 画像读取 | 总结工作流 | `style_profile.uk_sender` |
| 文件任务列表 | Vue | `file_task.idx_sender_status` |
| 解析会话的 Dify conversation_id | LangBot 每条消息 | `conversation_session.uk_session` |
| 认人：一个称呼对到哪些 QQ | `POST /api/ask`（resolve 之后） | `contact.idx_alias` |
| 某人的全部事实 | `POST /api/ask` 候选集 | `memory_note.idx_subject_pred` |
| 夜间归纳按游标扫新消息 | `@Scheduled` 00:10 | `message_log` PRIMARY（`id > cursor ORDER BY id LIMIT 200`） |
| 全文召回（实体/查询串） | `POST /api/ask` 的 `msg-mention` / `msg-lexical` | `message_log.ft_content`（ngram FULLTEXT） |
| 语义召回（全表向量） | `POST /api/ask` 的 `msg-semantic` | `message_embedding.idx_model` + 进程内 off-heap 索引 |
| 问答轨迹排查 | 漏答复盘 | `ask_trace.idx_created` |

---

## 五、Phase 2 落地要点（MyBatis-Plus）

- **主键**：`@TableId(type = IdType.AUTO)`（对应 BIGINT 自增）。
- **逻辑删除**：`draft` / `memo` 实体用 `@TableLogic` 标注 `is_deleted`；MyBatis-Plus 全局配置 `logic-delete-field`。
- **JSON 字段**：`draft.payload` / `file_task.detail` 用 `@TableField(typeHandler = JacksonTypeHandler.class)` + 实体字段声明为对象/Map。
- **枚举**：Java 枚举实现 `IEnum`，或直接用 `String` 字段，落库即 `VARCHAR` 语义值。
- **审计字段**：`created_at` / `updated_at` 由数据库默认值维护，实体侧用 `@TableField(fill = ...)` 亦可（二选一，勿重复写）。
- **DDL 交付**：本文 DDL 可直接作为 `schema.sql`；是否引入 Flyway/Liquibase 迁移，Phase 2 决定。

---

## 六、开放项

- **DDL 落地方式**：直接执行 `schema.sql` vs 接入 Flyway —— Phase 2 定。
- **`payload` 各 type 的 JSON Schema**：Phase 2 按类型定义并写入对应文档。
- **`message_log` 数据量增长**：个人项目量级可忽略；如需，Phase 2 加归档/清理策略。
