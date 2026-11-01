# F4+ 智能检索：跨会话、跨说话人、跨措辞

> 本文取代 `core_features.md` §7.2「纯夜间归纳」的取舍，以及 §7.4 末尾
> `:434`「不做向量数据库」的结论。推翻理由见 §1.3。
> 本文是 F4 的施工依据；实施计划在 `design/plans/`。

## 0. 一句话

问一句话，答案只要在**这台机器上任何一段聊天记录**里，就要找得到，并且给出它出自哪个会话、谁说的、什么时候。
找不到就明说找不到，不编。

**只在前端「查询」页使用**，不接入 QQ 端提问，插件不做任何改动。

## 1. 问题

### 1.1 今天的检索（`AskServiceImpl`）

两路并行：

- **按人**：`entities → contact → sender_id`，取这个人的 `memory_note` 和他**本人发的**消息。
- **按词**：`keywords` 直接 `message_log.content LIKE '%…%'`，**没有会话过滤**。

所以「答案在别的会话里」这件事，今天**部分**已经成立——只要原文里出现了问题里的那个词。
真正漏掉的是下面三种。

### 1.2 三个结构性漏洞

| # | 漏洞 | 证据 |
|---|---|---|
| 1 | **抽取层不给「被提到的人」归因**。主体只能是本批发言人或主人，小红说「小明生日 11 月 11」时，小明没在本批发言，这条事实记不到小明名下 | `design/memory_workflow.md:172`；`MemoryJobServiceImpl` 只用 `senders` 校验主体 |
| 2 | **只有字面匹配，没有语义召回**。「他 11 号生日」对不上「小明的生日」 | `AskServiceImpl.java:194-221` 全是 `LIKE` |
| 3 | **每源硬顶 30 条、先截断再问模型**。很旧的消息在进模型前就出局 | `AskServiceImpl.java:52-57`、`:226-235` |

### 1.3 为什么推翻「不做向量检索」

原来的理由是「数据量小，夜间归纳比向量检索准」。这在**结构化事实**上成立，
但它只覆盖「被正确归因、被正确措辞」的那部分事实。用户要的是**不漏**：

- 换说法、代词、口语 → 字面兜不住；
- 事实归属未知或跨会话 → 归纳兜不住；
- 两跳推理（小明室友是谁 → 先找到室友名）→ 单轮兜不住。

所以不是二选一，是**两条腿都要**：结构化事实做精确命中，向量 + 全文做召回兜底。
`core_features.md` §7.2 保留其「为什么不只做向量」的论证，但结论改为混合。

## 2. 总架构

```
                    ┌──────────── 索引（离线/增量）────────────┐
message_log ────────┤ FULLTEXT(ngram)  ·  message_embedding    │
memory_note  ───────┤ 归因后的结构化事实（三方归因）            │
                    └──────────────────────────────────────────┘
                                   │
问题 ─► Dify resolve ─► 七路召回 ─► RRF 融合 ─► Dify answer ─► 证据校验
        实体/关键词/     （Spring）     （Spring）              （Spring）
        改写/时间                                   │
                                                    ├─ 证据不足 → expand 再查（≤3 轮）
                                                    └─ 仍无 → 明说「没找到」+ 查过范围
```

职责边界不变：**语义判断在 Dify，检索、校验、截断、降级在 Spring**。Dify 不接触消息表。

## 3. 数据底座

### 3.1 `message_embedding`

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

- 只索引 `direction='IN'` 的消息。机器人发出的 `OUT` 不是证据。
- 768 维以内都在 3KB/条以内，10 万条约 200MB，MySQL 无压力。
- 源文本 = `content`；空内容不入索引（没有可检索的东西）。

### 3.2 全文索引

```sql
ALTER TABLE `message_log`
  ADD FULLTEXT INDEX `ft_content` (`content`) WITH PARSER ngram;
```

MySQL 8 自带 `ngram` 分词（`ngram_token_size` 默认 2），中文可用。
单字查询（如「李」）在 token=2 下匹配不到——个人场景里的人名、事项都 ≥2 字，可接受；
记忆页的人物检索按 alias 精确走 `contact`，不受影响。

`memory_note.value` 继续用 `LIKE`，它只有几百到几千行。

### 3.3 `ask_trace`（可选但要做）

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

用途：漏答时能回答「当时查了哪几路、各命中几条、向量索引落后多少」，而不是靠猜。
不存原文正文，只存问题与计数。

### 3.4 归因（不改表）

`memory_note.source_msg_ids` 已存在，足以回查。
新增规则见 §6。

## 4. 向量服务

### 4.1 模型

- **BGE-small-zh-v1.5**，512 维，CLS pooling，L2 归一化。
- 推理跑在 Spring 进程内：`com.microsoft.onnxruntime:onnxruntime`，模型文件不放仓库。
- Tokenizer 用**纯 Java WordPiece**（读 `vocab.txt`），不引 DJL / 不下载原生 tokenizer：
  避免「跑起来还要联网下 native」这类第二处失败点。BERT 中文分词规则简单，
  CJK 逐字 + 贪心最长匹配 + `##` 续接，`[CLS]`/`[SEP]` 补齐，上限 512 token。

拉模型的脚本 `scripts/fetch-embedding-model.sh`，从 `hf-mirror.com` 取
`Xenova/bge-small-zh-v1.5` 的 `onnx/model.onnx`（94.8MB）与 `vocab.txt`（107KB），
校验文件大小，落到 `./models/bge-small-zh-v1.5/`。模型目录进 `.gitignore`。

### 4.2 配置

```yaml
app:
  embedding:
    enabled: true
    mode: local                 # local | http
    model-dir: ./models/bge-small-zh-v1.5
    model-id: bge-small-zh-v1.5
    dim: 512
    http:                       # mode=http 时的 OpenAI 兼容端点
      base-url: ""
      api-key: ""
      model: ""
```

`mode=http` 是给不想在 Java 里跑模型的部署留的口子，默认不用。
`enabled=false` 或模型文件缺失 → **语义通道关闭**，其余六路照常，前端明确提示。
不允许因为向量不可用就整个 `/api/ask` 500。

### 4.3 查询期扫描

- 启动时把 `message_embedding` 全量读进一块 **off-heap `ByteBuffer`**（float32 连续存放），
  附带 `long[] messageIds`。
- 查询：把问题向量与全表做暴力余弦（向量已归一化，点积即余弦）。
  50 万条 × 512 维 ≈ 2.5 亿次乘加，Java 单线程约 0.2–0.5s，够用。
- 内存估算：50 万条 × 2KB ≈ 1GB off-heap（不计入堆）。到 50 万条以上再换 ANN 库，
  不在本期做；`ask_trace.candidate_cnt` 与启动日志会给出实际规模。
- 增量写入后同步更新缓冲：每次索引任务完成，按 id 顺序追加，不重建整块。

## 5. 索引任务

### 5.1 增量

`EmbeddingJobService` 每 60 秒跑一次：

```sql
SELECT m.id, m.content
FROM message_log m
LEFT JOIN message_embedding e
       ON e.message_log_id = m.id AND e.model = #{model}
WHERE m.direction = 'IN'
  AND m.content <> ''
  AND e.message_log_id IS NULL
ORDER BY m.id
LIMIT 64
```

- 用「缺行」驱动，不用游标：重启、换模型、删行都不会错位。
- 一批 64 条，失败保留缺口，下一轮重来。
- 写库与内存索引在同一步完成，避免「库里有、查询没有」。

### 5.2 查询前补跑

`/api/ask` 进入检索前先做一次 §5.1 的补跑，最多同步处理 500 条。
处理不完则在响应 `scope.lag` 里如实报剩余条数，前端显示「有 N 条新消息尚未建立语义索引」。
**不静默**。

### 5.3 重建

`POST /api/memory/embeddings/rebuild`（Body `{"model": "..."}`，可空）：
删掉该模型的全部行，按批重算。仅手动触发，不在夜间自动跑。

## 6. 归因修复（夜间 extract）

`memory_workflow.md:172` 的规则改为：

> `subject_sender_id` 可以取本批发言人的 `sender_id`，**也可以取 `known_contacts` 里、
> 且其 alias 在证据消息正文中出现过的联系人**；说主人自己时填 `null`。

Spring 侧硬校验（`MemoryJobServiceImpl.write`，逐条）：

1. 主体**亲自写了**本条 note 的证据消息（`source_msg_ids` 里至少一条的 `sender_id` 就是主体）；
   第一人称的事实（「我生日 11 月 11 日」）走这一条，正文里本来就不会出现自己的名字；
2. 否则：主体 ∈ 本批 `known_contacts` 的 senderId 集合，**且**主体的 alias（任一个）
   在 `source_msg_ids` 对应的消息正文里出现；
3. 两条都不满足 → **整条 note 丢弃**，记日志，不写库。

「主体在本批说过话」**本身不授权**。群聊一批两百条消息里几乎人人都是发言人，
若按「在本批发言」放行，模型把小红说的「我生日 11 月 11 日」记到同批的小明头上时，
这道闸门形同虚设——而那正是它存在的理由。

这是防「模型把小红的事记到小明头上」的唯一可靠闸门。提示词只负责倾向，校验负责正确。

副作用：如果某人从未发过言，就没有 `sender_id`，他名下不能有结构化事实。
这类事实仍能被 §7 的「名字出现」与「语义」两路找到，只是没有结构化条目。
用户在记忆页手动补一条 contact 后，下一轮归纳即可结构性归因。

## 7. 一次提问的完整链路

### 7.0 入口

`POST /api/ask`，请求体不变（`question` + 可选 `clarify`）。
响应新增 `scope`（见 §7.8），其余字段兼容。

### 7.1 查询理解（Dify `ask`, stage=resolve）

输入扩为：`question`、`known_contacts`、`owner_aliases`、`stage`、`current_time`。
工作流 Start 节点声明 `current_time`；Spring 在 `stage=resolve` 与 `stage=expand` 时传它
（ISO-8601 本地时间、秒精度，如 `2026-10-31T21:14:00`），`stage=answer` 不传。
输出：

| 字段 | 说明 |
|---|---|
| `entities` | 人名，尽量照抄 alias |
| `keywords` | 2–6 个可检索实词 |
| `queries` | **新增**：2–4 条改写/同义检索串 |
| `time_hint` | 原始时间说法，如「上周」「10 月」；只用于答题提示 |
| `time_from` | **新增**：规范化的起始时间 `YYYY-MM-DDTHH:MM`，可空 |
| `time_to` | **新增**：规范化的结束时间，可空 |

**相对时间由 Dify 换算，Spring 不做中文日期解析。** 理由：Spring 把 `current_time` 交给
Dify（见 `stage=resolve` / `stage=expand` 的输入），把「上周」「去年十月」换算成绝对区间是语义判断；Spring 再写一套
中文日期解析器只会多一个出错点。Spring 只做格式校验：解析不了就两个都置空，
按无时间提示处理，不报错。

`queries` 最多 4 条。词法通道每条都跑；语义通道只用**前 3 条**（原问题 + 最多 2 条改写），
见 §7.4「一个查询向量」。

如果 resolve 调用失败：把原问题整句当 `queries[0]`，`entities` 为空、
`time_from/time_to` 为空，继续检索。**理解失败不等于放弃检索。**

### 7.2 认人

- 每个 entity 查 `contact.alias`：1 条 → 主体；≥2 条 → 反问（同今天）；0 条 → **不立刻反问**。
- 0 条时记入 `unknownAliases`，照常走下面的召回；名字本身会作为检索词。
  若最终证据足够 → 正常作答，并在 `scope.message` 里注明「未登记的联系人，按名字检索」；
  证据不足 → 再触发反问，并附上出现次数。

**`occurrences` 的定义**（新增到 `ClarifyStateVO`）：该 alias 在
`message_log`（`direction='IN'`）正文里作为子串出现的行数，**跨全部会话**。
用 `LIKE '%alias%'` 精确数，个人量级不需要 FULLTEXT；上限显示 999。
它回答的是「这个名字到底有没有在记录里出现过」：
- `occurrences = 0` → 这个名字哪儿都没有，反问没有意义，直接按「没找到」返回；
- `occurrences > 0` → 反问「记录里出现过 N 次，这是谁？」，用户答完写 `contact(source=USER)`。

### 7.3 七路召回（并行，全部不带会话过滤）

| 通道 | 来源 | 上限 |
|---|---|---|
| `note` | 主体 = 解析出的人 | 200 |
| `note` | `value/predicate LIKE` 任一 keyword | 100 |
| `msg-person` | 主体本人发的消息（`sender_id`） | 200 |
| `msg-mention` | `MATCH(content) AGAINST` 任一 entity alias | 100 |
| `msg-lexical` | `MATCH(content) AGAINST` 任一 `queries` | 100/条 |
| `msg-semantic` | 全表向量 top-K | 100 |
| `memo` | 标题/正文 LIKE 任一 keyword | 50 |
| `file` | 文件名 LIKE 任一 keyword | 30 |

> 表里 8 行归并为 **5 个通道名**：`note`（前两行）、`msg-person`、`msg-mention`、
> `msg-lexical`、`msg-semantic`；`memo` / `file` 各自是一个通道。所以
> `scope.channels` 的键固定为 7 个：`note / msg-person / msg-mention / msg-lexical /
> msg-semantic / memo / file`。
> 计数口径：**每路原始命中条数、按查询串累加、去重前**；因此各通道之和可以大于
> `candidateCnt`（同一个 key 被多路命中是正常的，也是我们想要的冗余）。

「名字出现」通道是解决用户原始例子的主力：小红说「小明生日」，
`MATCH(content) AGAINST ('小明')` 必然命中，与谁说的无关。

### 7.4 融合与截断

- 每路按自身分数（FULLTEXT 的 score、余弦相似度、结构化查询的时间倒序）排出名次。
- **RRF**（`score = Σ 1/(60 + rank)`）融合去重，按 key `type:id` 去重。
  跨轮累计时，同一候选取它**最好的一次名次**参与融合，不重复累加。
- 保底：语义与词法各至少保留前 10 条，避免某一路被结构化结果淹没。
- 时间提示存在时做**软分段**：命中区间内的候选整体排在区间外之前，两段内部仍按 RRF。
  区间为空或时间不可解析就整段跳过。不硬过滤——宁可多给。
- 按 RRF 分数取 top，直到字符预算 **30000** 或 **150 条**。预算只在**最后一次**答题调用时生效，
  中间轮次的候选先全留着。

**一个查询向量**：语义通道对 `queries` 的前 3 条分别取向量，**逐元素求平均后重新归一化**，
只扫一遍全表。这样既覆盖改写，又不把查询延迟乘以条数。取平均而不是取最大相似度，
是为了让「原问题」始终占一半以上权重，避免某条改写把检索带偏。

### 7.5 作答与校验

Dify `ask` stage=answer 输入：`question`、`candidates`（**跨轮累计后的完整候选集**）、
`time_hint`。输出 `answer`、`used_ids`。

Spring 校验（不变）：

- `used_ids ⊆ 候选集`（跨轮累计后仍然成立，前几轮用过的 id 依然合法）；
- 有 `answer` 就必须有 `used_ids`；
- 否则 `degraded=true`，只回证据。

**证据字段**（`EvidenceVO` 增加两个可选字段，`content` 语义不变）：

| 字段 | 内容 |
|---|---|
| `label` | 仍是一行摘要，如「小明 · 生日」 |
| `content` | 事实本身，如「11 月 11 日」 |
| `source` | **新增**：出处，格式化字符串。群聊 `三班群 · 小明 · 10-28 21:03`；私聊 `QQ 123456 · 小红 · 10-28 21:03`；无发送人名时退回 QQ 号。多条来源用 `；` 连接，最多 3 条，其余以「等 N 条」收尾——**N 是剩余条数**：5 条来源显示前 3 条后写「等 2 条」，不是「等 5 条」 |
| `quote` | **新增**：第一条来源消息的原文，截 200 字。让用户能看见「这话是谁在哪个会话里说的」 |

私聊没有群名，展示用「QQ <对方QQ号>」，与 `ChatSettingService` 的兜底命名一致。

实现上，一次答题结束后把所有 `note` 候选的 `source_msg_ids` 收集起来，
**一次** `SELECT ... WHERE id IN (...)` 查回 `message_log`，在内存里组装，不逐条查。

这是「跨会话」在界面上的可见证据：结论旁边直接写着它出自小红的私聊。

### 7.6 多轮（≤3 轮）

第 1 轮 `answer` 为空或降级时：

Dify `ask` **新增 stage=expand**：输入 `question` + 已命中候选的摘要 + `current_time`，
输出 `queries`（新检索串）、`entities`（新发现的人名）、`time_hint` / `time_from` / `time_to`
（End 节点实际输出名为 `expand_*`，见 §8.4）。
Spring 用新词再跑 §7.3，**检索时排除已见过的 id**（省重复计算），
但候选集是**跨轮累计**的：第 2 轮的 answer 看得到第 1 轮的事实。

典型两跳：第 1 轮找到「小明和学霸住一屋」→ expand 抽出「学霸」→
第 2 轮找到「学霸叫李四」→ 答题时两条都在候选集里，才能得出「李四」。
如果只把最新一轮交给模型，这个用例永远过不了。

累计规则：
- 候选按 key `type:id` 合并，保留最好名次；
- `scope.channels` 是各轮之和；
- `candidateCnt` 是**去重后、预算截断前**的候选数；
- 答题前才做 30k/150 的预算截断。

最多 3 轮（含第 1 轮）。第 3 轮仍无结论就进 §7.7 的终态。

### 7.7 终态：反问 / 降级 / 找不到

**终态只有一张表，优先级从上到下**：

| # | 条件 | `answer` | `degraded` | `evidence` | `needClarify` |
|---|---|---|---|---|---|
| 1 | 某 alias 对应 ≥2 个 sender | `""` | false | `[]` | 候选列表（重名） |
| 2 | 模型给了结论，且 `used_ids` 合法非空 | 结论 | false | `used_ids` 对应 | null |
| 3 | 模型给了结论，但 `used_ids` 越界或为空 | `""` | **true** | 候选前 5 条 | null |
| 4 | 没有结论 + 未知 alias 且 `occurrences>0` | `""` | false | 候选前 5 条 | alias + occurrences |
| 5 | 没有结论 + 未知 alias 且 `occurrences=0` | `""` | false | `[]` | null（`scope.message` 说明这个称呼没出现过） |
| 6 | 没有结论 + 有候选 | `""` | false | 候选前 5 条 | null（`scope.message`：找到相关记录，但没能得出结论） |
| 7 | 没有结论 + 没有候选 | `""` | false | `[]` | null（`scope.message`：没有找到相关记录，已查过……） |

- 第 1 条优先于一切：名字都有歧义时，先问清是谁，不猜。
- 第 3 条与第 2 条的差别只有一个：模型有没有给出可追溯的证据。
- 第 4 条是「这个名字在记录里出现过，但我不知道它是谁」——反问有意义。
- 第 5 条是「这个名字根本没出现过」——反问没有意义，直接说找不到。

前端按 `needClarify != null` → 反问表单；`degraded` → 只列证据并提示「结论不可靠」；
其余情况显示 `answer`，并把**非空的** `scope.message` 一并显示——成功作答也可能带提示
（终态第 2 条 + 未登记联系人：「未登记的联系人，按名字检索」）。
`needClarify` 与 `degraded` 互斥；`scope.message` 是补充说明，不改变结论。

### 7.8 响应新增字段

```json
"scope": {
  "semantic": true,          // 语义通道是否可用
  "lag": 0,                  // 尚未建立向量的消息数
  "rounds": 2,               // 实际跑了几轮（1–3）
  "candidateCnt": 47,        // 去重、预算截断前的候选数（响应字段是 camelCase）
  "channels": {
    "note": 3,
    "msg-person": 5,
    "msg-mention": 8,
    "msg-lexical": 12,
    "msg-semantic": 20,
    "memo": 0,
    "file": 0
  },
  "message": ""
}
```

- `channels` 的**键固定为这 7 个，永远出现**；值为 `null` 表示该通道没跑或不可用
  （例如语义模型没就绪、某路 SQL 报错），`0` 表示跑了但没有命中。两者不能混。
- 计数是「每路原始命中、按查询串累加、去重前」，跨轮累加，所以总和可以大于 `candidateCnt`。
  （数据库列仍叫 `ask_trace.candidate_cnt`，那是 SQL 列名；响应字段一律 camelCase。）
- `message` 是给人看的一句话，前端原样显示。空串表示无需解释。

向后兼容：老前端忽略该字段即可。

## 8. Dify 工作流改动

文件：`design/QQAssistant-memory-workflow.yml`（改完在控制台导入并发布）。

1. Start：声明 `question`、`stage`、`known_contacts`、`owner_aliases`、`candidates`、`time_hint`，
   以及 `current_time`（Spring 在 `stage=resolve` / `stage=expand` 传，见 §7.1）。
2. `is_resolve` 分支：`resolve_llm` 的提示词加 `queries` 输出；`parse_resolve` 校验并序列化为 JSON 数组。
3. `is_answer` 分支：`answer_llm` 提示词强调「先看 msg-mention 类证据，它可以说明别人怎么提到这个人」，
   其余规则不变。
4. 新增 `is_expand` 分支 + `expand_llm` + `parse_expand`。`parse_expand` 的内部键保持
   `queries` / `entities` / `time_hint` / `time_from` / `time_to`；但 **End 节点的输出名必须是
   `expand_queries` / `expand_entities` / `expand_time_hint` / `expand_time_from` / `expand_time_to`**
   ——Dify 要求 End 节点输出名全局唯一，不能与 resolve 的同名输出撞。因此 `task=ask`,`stage=expand`
   实际返回：

   ```json
   {"expand_queries":["学霸 名字","李四 学霸"],"expand_entities":["学霸"],
    "expand_time_hint":"","expand_time_from":"","expand_time_to":""}
   ```

   Spring 只读 `expand_*`；`parse_expand` 的键不直接出现在响应里。
5. 所有解析节点对非 JSON 输入返回空结构，不抛异常。

提示词里**不写**检索上限、RRF、证据校验规则——那些在 Spring。

## 9. 前端

`AskView.vue` / `api/ask.ts`：

1. `Evidence` 增加可选的 `source` 与 `quote`（见 §7.5），由 Spring 组装，前端直接显示；
   `quote` 默认折叠，点证据才展开原文。
2. 展示 `scope`：
   - 语义通道不可用（`semantic=false`）→ 顶部提示「语义检索未启用（模型未就绪），当前只做全文与结构化检索」；
   - `lag > 0` → 「有 N 条新消息尚未建立语义索引」；
   - `scope.message` **非空就显示，即使 `answer` 也非空**（例如成功作答但提示「未登记的联系人，按名字检索」，见 §7.2/§7.7）；
     找不到时同时显示各通道计数，值为 `null` 的通道不显示。
3. `needClarify` 文案用上 `occurrences`：「记录里出现过 N 次，能说说是谁吗？」；
   `occurrences=0` 时不会走到反问，见 §7.7 第 5 条。

不做：检索范围筛选器、结果列表分页、把原文整段展开（点一下再展开）。

## 10. 失败与降级

| 情况 | 行为 |
|---|---|
| 模型文件缺失 / ONNX 初始化失败 | 语义通道关闭，`scope.semantic=false`，其余六路照常；日志给出模型路径 |
| Dify resolve 失败 | 原问题当检索串，继续七路召回 |
| Dify answer 失败 | 只回证据，`degraded=true`，不编答案 |
| 向量索引滞后 | 查询前同步补跑 ≤500 条；剩余在 `scope.lag` 如实上报 |
| 某一路 SQL 报错 | 该路记日志跳过，在 `scope.channels` 里置 `null`（键仍在）；其余路继续 |
| 候选集为空 | 仍跑 `expand` 一轮（可能是措辞问题），再空才判定「没找到」 |

**一条硬规则：任何单点失败都不能让整个 `/api/ask` 变成 500。**

## 11. 验证

### 11.0 上线顺序（含历史重抽）

1. 执行迁移：两张表 + FULLTEXT。
2. 启动新 Spring，索引任务自动补历史向量（10 万条约 2–4 分钟；可后台跑）。
3. **手动触发一次 `POST /api/memory/rebuild`**：游标归零，按新归因规则重抽全部历史。
   去重按 `(subject, predicate, value)` 挡住重复行，重跑是安全的。
   **这是让旧数据也能结构化归因的唯一手段**；不做就只对上线后的消息生效，
   历史里的跨会话事实只能靠 §7 的召回通道找到。
4. 发布前端。
5. 在 Dify 控制台重新导入并发布 `QQAssistant-memory-workflow.yml`（新增 `queries`、
   `time_from/time_to`、`stage=expand`）。

第 3 步会消耗 Dify token，量随历史长度线性增长，由使用者决定什么时候跑；
不放进夜间自动任务。

### 11.1 自动化（`fake EmbeddingService`，不依赖模型）

种子数据固定为 6 个会话、80 条消息，覆盖下面的矩阵：

| # | 场景 | 期望 |
|---|---|---|
| 1 | 「小明生日」只在**小红的私聊**里出现 | 命中该条，证据显示「小红私聊」 |
| 2 | 消息用「他 11 号生日」，问「小明生日」 | 语义通道命中（fake 向量把两者置近） |
| 3 | 很旧的消息（排在 1 万条之前） | 命中，验证无 30 条硬顶 |
| 4 | 两跳：A 会话「小明跟学霸住一屋」，B 会话「学霸叫李四」 | 2 轮内命中李四 |
| 5 | 问「阿呆」，记录里出现过但不在 contact | 不立即反问，能作答并带 `scope.message` |
| 6 | 问一件根本没发生的事 | `answer=""`，`scope.message` 非空，不编 |
| 7 | 模型引用候选集外 id | `degraded=true`，只回证据 |
| 8 | 新消息未入索引 | 查询前补跑后命中；若超过 500 条，`scope.lag>0` |
| 9 | 语义服务不可用 | 其余六路仍能答，`scope.semantic=false` |
| 10 | Dify resolve 抛错 | 仍能返回结果，不 500 |
| 11 | 同一 alias 对应两个 sender | 终态第 1 条：`needClarify`，且 `scope.channels` 全为 `null`（没检索） |
| 12 | 未知 alias，记录里出现 3 次，答不出 | 终态第 4 条：`needClarify` 且 `occurrences=3` |
| 13 | 未知 alias，全库没有 | 终态第 5 条：`needClarify=null`，`scope.message` 说明该称呼没出现过 |
| 14 | 第 2 轮答案引用第 1 轮候选的 id | 不判越界（跨轮累计生效） |
| 15 | `time_from/time_to` 覆盖某几条消息 | 区间内整体排在区间外之前，区间外仍保留 |
| 16 | 只有「3 条查询的平均向量」能命中的构造用例 | 语义通道命中（验证平均而非只用原问题） |

另加：
- `WordPieceTokenizerTest`：中文、英文、数字、超长截断、`[CLS]/[SEP]`。
- `OnnxEmbeddingServiceTest`（`@Tag("model")`，本地模型存在才跑）：同义句余弦 > 0.7，
  无关句 < 0.4；向量已归一化（模长 1±1e-5）。
- `AttributionTest`：三方归因通过校验；alias 不在原文 → 整条丢弃；主体不在本批/known_contacts → 丢弃。
- `AskTraceTest`：一次问答写一行，计数与通道名正确。

### 11.2 真机

1. 拉模型、启动、`GET /api/memory/embeddings/status` 显示 `semantic=true`。
2. 手工灌 3 个会话的消息（含跨会话提到同一个人），`POST /api/ask` 问该人的属性。
3. 断网/改错模型目录重启 → `semantic=false`，问答仍可用。
4. `POST /api/memory/embeddings/rebuild` → 行数不变，索引可用。

## 12. 非目标

- 不做 QQ 端提问；插件不改。
- 不检索文件正文（`file_task` 只按文件名/请求检索）。
- 不做跨用户共享、不做云端同步。
- 不做 ANN 索引（HNSW/IVF），50 万条以上再说。
- 不做知识库（Dify Dataset）：数据源在 MySQL，双写会引入不一致；证据 id 与原文映射也会变脆。
- 不追求形式上的 100%：事实从未被说出口时，任何系统都查不到；本文的目标是把其余全部堵上。

## 13. 代价

| 项 | 成本 |
|---|---|
| 一次性 | 95MB 模型 + ONNX Runtime 依赖；全量向量化（10 万条约 2–4 分钟 CPU） |
| 持续 | 每 60 秒一次小批量推理；每次问答多 1–3 次 Dify 调用 |
| 内存 | 语义索引 off-heap，10 万条约 200MB，50 万条约 1GB |
| 维护 | 换模型 = 全量重建；FULLTEXT 索引随写入自动维护 |
| 新增失败点 | 模型加载、ONNX 推理、FULLTEXT 语法（`ngram` 需 MySQL 8.0+，本项目 8.4 满足） |

## 14. 需要同步修订的文档

- `core_features.md` §7.2 结论改为「混合检索」，`:434` 的「不做向量数据库」标注为已推翻。
- `memory_workflow.md` §1.2 / §4.2 的归因规则与硬校验。
- `api_contract.md` `/api/ask` 增加 `scope`，新增两个 embedding 管理端点。
- `database_schema.md` 增加两张表与 FULLTEXT。
- `architecture.md` 的检索职责说明。
- `deploy/README.md` 补模型下载步骤。
