# 智能消息检索 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 `/api/ask` 在全部会话、全部说话人、任意措辞下找回答案，并给出可回溯的出处；找不到就明说。

**Architecture:** MySQL 存原文与结构化记忆，新增向量表与 `ngram` 全文索引；Spring 进程内跑 BGE-small-zh 的 ONNX 推理与暴力余弦；七路召回经 RRF 融合后交给 Dify 作答，Spring 校验证据；证据不足时最多再查两轮。Dify 只做语义理解与成文，检索、截断、校验、降级全在 Spring。

**Tech Stack:** Spring Boot 3.4.12 / Java 21 / MyBatis-Plus / MySQL 8.4（ngram FULLTEXT）/ onnxruntime-java / Dify Workflow / Vue3 + TS。

**Spec:** `design/intelligent_retrieval.md`

## Global Constraints

- **不自动提交 git。** 本计划的每一步都不含 `git commit`；需要提交时先问用户。
- 不改 `MessageController.java` 里的 Dify API key；不打印 `application.yaml` 里的数据库口令与 Dify key；不打印 `langbot.db` 里的 key。
- 不删除 LangBot 数据；不改 `QQMessageAssistantPython/`（本计划不碰插件）。
- 文件改写/文件填写功能冻结，不顺手改。
- 所有时间字段用 `LocalDateTime`，`YYYY-MM-DDTHH:MM`。
- 响应 JSON 用驼峰（与现有 `needClarify` 一致）：spec §7.8 的 `candidate_cnt` 落地为 `candidateCnt`，Task 13 同步改 spec 示例。
- 证据 key 一律带类型前缀：`msg:` / `note:` / `memo:` / `file:`。
- 检索失败不能让 `/api/ask` 变成 500：单路失败只把该路记为 `null`。
- 测试跑在 `qq_assistant_test` 库；`schema.sql` 用 `CREATE TABLE IF NOT EXISTS`，索引类变更走迁移脚本并在两个库各执行一次。

## Review Focus

1. **同一问题里两个人名，一个重名一个唯一** → 必须先反问重名的那个，不能先答后问。
2. **`content` 为 NULL 或空串的消息** → 不进入向量索引，也不让 FULLTEXT 查询报错。
3. **alias 里含 `%`、`_`、反斜杠** → `occurrences` 与 `LIKE` 检索必须转义，否则计数虚高。
4. **超过 512 token 的长消息** → 向量截断后仍能被词法通道找到，不因截断丢失。
5. **模型文件存在但损坏/维度不符** → 启动不崩，语义通道降级为不可用，其余六路照常。

---

### Task 1: 数据库迁移、实体与 Mapper

**Files:**
- Create: `QQMessageAssistantJava/src/main/resources/db/migrations/2026-10-31-embedding-retrieval.sql`
- Modify: `QQMessageAssistantJava/src/main/resources/db/schema.sql`（新增两张表；`message_log` 的建表语句里加 FULLTEXT，供全新库）
- Create: `QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/entity/MessageEmbedding.java`
- Create: `QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/entity/AskTrace.java`
- Create: `QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/mapper/MessageEmbeddingMapper.java`
- Create: `QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/mapper/AskTraceMapper.java`
- Modify: `QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/mapper/MessageLogMapper.java`
- Test: `QQMessageAssistantJava/src/test/java/com/czx/qqmessageassistantjava/mapper/RetrievalFoundationTest.java`

**Interfaces:**
- Produces:
  - `MessageEmbedding{messageLogId:Long, model:String, dim:Integer, vector:byte[], createdAt:LocalDateTime}`
  - `MessageEmbeddingMapper.upsert(MessageEmbedding) : int`
  - `MessageEmbeddingMapper.selectMissing(String model, int limit) : List<MissingMessage>`，`record MissingMessage(long id, String content)`
  - `MessageEmbeddingMapper.selectAll(String model, ResultHandler<MessageEmbedding>) : void`（流式）
  - `MessageEmbeddingMapper.countByModel(String model) : long`、`deleteByModel(String model) : int`
  - `AskTraceMapper extends BaseMapper<AskTrace>`
  - `MessageLogMapper.searchFulltext(String q, int limit) : List<MessageLog>`
  - `MessageLogMapper.countMentions(String alias) : long`

- [ ] **Step 1: 写迁移脚本与 schema**

`migrations/2026-10-31-embedding-retrieval.sql`：

```sql
CREATE TABLE IF NOT EXISTS `message_embedding` (
  `message_log_id` BIGINT UNSIGNED NOT NULL,
  `model`          VARCHAR(64) NOT NULL,
  `dim`            SMALLINT NOT NULL,
  `vector`         MEDIUMBLOB NOT NULL,
  `created_at`     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`message_log_id`,`model`),
  KEY `idx_model` (`model`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消息向量';

CREATE TABLE IF NOT EXISTS `ask_trace` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `question`       VARCHAR(512)  NOT NULL,
  `entities`       VARCHAR(512)  NULL,
  `queries`        VARCHAR(1024) NULL,
  `channels`       VARCHAR(128)  NULL,
  `candidate_cnt`  INT NOT NULL DEFAULT 0,
  `rounds`         TINYINT NOT NULL DEFAULT 1,
  `answer`         VARCHAR(1024) NULL,
  `degraded`       TINYINT NOT NULL DEFAULT 0,
  `latency_ms`     INT NULL,
  `created_at`     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='一次问答的检索轨迹';

SET @has_ft = (SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'message_log' AND index_name = 'ft_content');
SET @sql = IF(@has_ft = 0,
  'ALTER TABLE `message_log` ADD FULLTEXT INDEX `ft_content` (`content`) WITH PARSER ngram',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
```

`schema.sql`：把上面两张 `CREATE TABLE` 原样追加；在 `message_log` 建表语句的 `KEY idx_chat_created ...` 后加
``FULLTEXT KEY `ft_content` (`content`) WITH PARSER ngram``（只对全新库生效）。

- [ ] **Step 2: 在开发库与测试库各执行一次迁移**

Run:
```bash
MYSQL_PWD=root /e/develop/MySQL/mysql-8.4.7-winx64/bin/mysql -uroot -h192.168.100.128 -P3307 qq_assistant < QQMessageAssistantJava/src/main/resources/db/migrations/2026-10-31-embedding-retrieval.sql
MYSQL_PWD=root /e/develop/MySQL/mysql-8.4.7-winx64/bin/mysql -uroot -h192.168.100.128 -P3307 qq_assistant_test < QQMessageAssistantJava/src/main/resources/db/migrations/2026-10-31-embedding-retrieval.sql
```
Expected: 无报错；`SHOW INDEX FROM message_log WHERE Key_name='ft_content'` 在测试库有 1 行。

- [ ] **Step 3: 写失败测试 `RetrievalFoundationTest`**

`@SpringBootTest @ActiveProfiles("test") @Transactional`，断言：

```java
@Test void embedding_roundTrips() {
    MessageEmbedding e = new MessageEmbedding();
    e.setMessageLogId(9_900_001L); e.setModel("m1"); e.setDim(4);
    e.setVector(new byte[]{0,0,(byte)0x80,0x3f, 0,0,0,0x40, 0,0,0x40,0x40, 0,0,(byte)0x80,0x3f});
    mapper.upsert(e);
    MessageEmbedding back = mapper.selectOne(9_900_001L, "m1");
    assertThat(back.getDim()).isEqualTo(4);
    assertThat(back.getVector()).hasSize(16);
}

@Test void fulltext_finds_chinese_without_sender_filter() {
    insertIn(9_900_101L, 100L, "小红", "小明生日是11月11");
    insertIn(9_900_102L, 200L, "小明", "今天好热");
    assertThat(messageLogMapper.searchFulltext("小明生日", 10))
        .extracting(MessageLog::getId).contains(9_900_101L);
}

@Test void countMentions_escapes_like_wildcards() {
    insertIn(9_900_201L, 100L, "小红", "折扣 50% 起");
    assertThat(messageLogMapper.countMentions("50%")).isEqualTo(1);
    assertThat(messageLogMapper.countMentions("5%0")).isEqualTo(0);
}
```

- [ ] **Step 4: 运行，确认失败**

Run: `cd QQMessageAssistantJava && mvn -q -Dtest=RetrievalFoundationTest test`
Expected: 编译失败（类不存在）。

- [ ] **Step 5: 实现实体、Mapper 与 `likeEscape`**

`MessageLogMapper.countMentions` 的 SQL：
`... WHERE direction='IN' AND content LIKE CONCAT('%', #{alias}, '%') ESCAPE '\\\\'`，
并在 Java 侧提供 `static String likeEscape(String s)`：转义 `\`、`%`、`_`，在传给 Mapper 前调用。
`searchFulltext` 用 `MATCH(content) AGAINST(#{q} IN NATURAL LANGUAGE MODE)`，按同一表达式倒序，`LIMIT #{limit}`。

- [ ] **Step 6: 运行测试通过**

Run: `mvn -q -Dtest=RetrievalFoundationTest test`
Expected: 3 个测试全 PASS。

---

### Task 2: 纯 Java WordPiece 分词器

**Files:**
- Create: `QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/embedding/WordPieceTokenizer.java`
- Test: `QQMessageAssistantJava/src/test/java/com/czx/qqmessageassistantjava/embedding/WordPieceTokenizerTest.java`
- Test resource: `QQMessageAssistantJava/src/test/resources/embedding/vocab-fixture.txt`

**Interfaces:**
- Produces: `WordPieceTokenizer(Path vocabTxt)`；`List<Integer> encode(String text, int maxLen)`；
  `int clsId()`、`int sepId()`、`int padId()`；`int vocabSize()`。

- [ ] **Step 1: 写 fixture 词表与失败测试**

`vocab-fixture.txt` 包含：`[PAD] [UNK] [CLS] [SEP] 小 明 生 日 你 好 ##生 ##日 的 a b`（每行一个）。

```java
@Test void chinese_chars_become_single_tokens() {
    var t = new WordPieceTokenizer(Path.of("src/test/resources/embedding/vocab-fixture.txt"));
    assertThat(t.encode("小明", 16))
        .containsExactly(t.clsId(), t.tokenId("小"), t.tokenId("明"), t.sepId());
}

@Test void long_input_is_truncated_to_max_len() {
    var t = new WordPieceTokenizer(...);
    var ids = t.encode("小明生日你好".repeat(50), 8);
    assertThat(ids).hasSize(8);
    assertThat(ids.get(0)).isEqualTo(t.clsId());
    assertThat(ids.get(7)).isEqualTo(t.sepId());
}

@Test void unknown_char_falls_back_to_unk() {
    assertThat(t.encode("龘", 8)).contains(t.unkId());
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -Dtest=WordPieceTokenizerTest test`
Expected: 编译失败。

- [ ] **Step 3: 实现分词器**

规则：`[CLS]` + 文本按「空白/标点切分，CJK 逐字」后贪心最长匹配（续接词带 `##`）+ `[SEP]`；
超过 `maxLen` 时保留首尾（`[CLS] … [SEP]`），中间截断。词表用 `HashMap<String,Integer>`。

- [ ] **Step 4: 运行通过**

Run: `mvn -q -Dtest=WordPieceTokenizerTest test`
Expected: 3 个全 PASS。

---

### Task 3: EmbeddingService 与 ONNX 实现

**Files:**
- Create: `QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/embedding/EmbeddingService.java`
- Create: `QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/embedding/EmbeddingProperties.java`
- Create: `QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/embedding/OnnxEmbeddingService.java`
- Create: `QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/embedding/HttpEmbeddingService.java`
- Create: `QQMessageAssistantJava/scripts/fetch-embedding-model.sh`
- Modify: `QQMessageAssistantJava/pom.xml`（加 `com.microsoft.onnxruntime:onnxruntime:1.20.0`）
- Modify: `.gitignore`（`models/`）
- Test: `QQMessageAssistantJava/src/test/java/com/czx/qqmessageassistantjava/embedding/OnnxEmbeddingServiceTest.java`

**Interfaces:**
- Produces: `EmbeddingService{boolean available(); String modelId(); int dim(); float[] embed(String); List<float[]> embedBatch(List<String>);}`
- `EmbeddingProperties`（prefix `app.embedding`）：`enabled=true`、`mode="local"`、`modelDir="./models/bge-small-zh-v1.5"`、`modelId="bge-small-zh-v1.5"`、`dim=512`、`http{baseUrl,apiKey,model}`。

- [ ] **Step 1: 写拉模型脚本**

`scripts/fetch-embedding-model.sh`：`curl -L` 从 `https://hf-mirror.com/Xenova/bge-small-zh-v1.5/resolve/main/`
取 `onnx/model.onnx`（94951877 字节）与 `vocab.txt`（109540 字节）到 `./models/bge-small-zh-v1.5/`；
大小不符则 `exit 1`。不提交模型文件。

- [ ] **Step 2: 写失败测试**

```java
@Test void model_produces_normalized_vectors_and_similarity() {
    Assumptions.assumeTrue(Files.exists(Path.of("./models/bge-small-zh-v1.5/model.onnx")),
        "模型未下载，跳过真模型测试");
    var svc = OnnxEmbeddingService.create(propsWithModelDir(...));
    float[] a = svc.embed("小明的生日是哪天");
    float[] b = svc.embed("小明出生日期");
    float[] c = svc.embed("今天会下雨吗");
    assertThat(a).hasSize(512);
    assertThat(norm(a)).isCloseTo(1.0, within(1e-5));
    assertThat(cos(a, b)).isGreaterThan(0.7f);
    assertThat(cos(a, c)).isLessThan(0.4f);
}

@Test void missing_model_degrades_without_throwing() {
    var svc = OnnxEmbeddingService.create(propsWithModelDir(Path.of("./no-such-dir")));
    assertThat(svc.available()).isFalse();
}

@Test void corrupt_model_file_degrades_without_throwing() throws Exception {
    Path dir = Files.createTempDirectory("bad-model");
    Files.writeString(dir.resolve("vocab.txt"), "[PAD]\n[UNK]\n[CLS]\n[SEP]\n");
    Files.write(dir.resolve("model.onnx"), new byte[]{1, 2, 3});
    var svc = OnnxEmbeddingService.create(propsWithModelDir(dir));
    assertThat(svc.available()).isFalse();
}
```

- [ ] **Step 3: 运行确认失败**

Run: `mvn -q -Dtest=OnnxEmbeddingServiceTest test`
Expected: 编译失败。

- [ ] **Step 4: 实现**

- `available()` 在构造时探测 `model.onnx` 与 `vocab.txt`，并 try/catch 打开会话；缺失或加载失败都返回 false（记日志，不抛），`embed` 抛 `BusinessException("语义检索未启用：模型未就绪")`。
- 推理：`OrtSession` 输入 `input_ids/attention_mask/token_type_ids`（按 `getInputNames()` 决定是否喂 `token_type_ids`），
  输出取第一个 3 维张量的第 0 批第 0 号 token（CLS），L2 归一化。
- `embedBatch` 逐条调用（批量 padding 属优化，本期不做）。
- `HttpEmbeddingService` 用 `RestClient` 调 OpenAI 兼容 `/embeddings`，失败抛 `BusinessException`；
  由 `@ConditionalOnProperty(prefix="app.embedding", name="mode", havingValue="http")` 选择。

- [ ] **Step 5: 下载模型并跑通过**

Run:
```bash
cd QQMessageAssistantJava && bash scripts/fetch-embedding-model.sh
mvn -q -Dtest=OnnxEmbeddingServiceTest test
```
Expected: 模型文件大小正确；2 个测试 PASS（同义句 >0.7，无关句 <0.5，模长 1±1e-4）。

---

### Task 4: EmbeddingIndex（内存暴力余弦）

**Files:**
- Create: `QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/embedding/EmbeddingIndex.java`
- Test: `QQMessageAssistantJava/src/test/java/com/czx/qqmessageassistantjava/embedding/EmbeddingIndexTest.java`

**Interfaces:**
- Produces:
  - `void reset()`
  - `void append(long messageId, float[] vector)`
  - `List<ScoredId> search(float[] query, int topK)`
  - `int size()`、`boolean contains(long messageId)`
  - `record ScoredId(long messageId, float score)`

- [ ] **Step 1: 写失败测试**

```java
@Test void search_returns_nearest_first() {
    var idx = new EmbeddingIndex(3);
    idx.append(1, vec(1,0,0)); idx.append(2, vec(0,1,0)); idx.append(3, vec(0.9f,0.1f,0));
    assertThat(idx.search(vec(1,0,0), 2)).extracting(ScoredId::messageId).containsExactly(1L, 3L);
}

@Test void reset_then_append_grows_without_rebuild() { /* append 2 条，reset，append 1 条，size()==1，能被搜到 */ }

@Test void dimension_mismatch_is_rejected() {
    assertThatThrownBy(() -> idx.append(9, vec(1,0))).isInstanceOf(IllegalArgumentException.class);
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -Dtest=EmbeddingIndexTest test`
Expected: 编译失败。

- [ ] **Step 3: 实现**

`long[] ids` + `ByteBuffer.allocateDirect(capacity*dim*4)`，容量不够时翻倍重建；
`search` 用大小为 `topK` 的最小堆，遍历 `0..size` 做点积（向量已归一化）。
`append` 与 `search` 都 `synchronized`，且 `search` 在锁内完成整次扫描。

- [ ] **Step 4: 运行通过**

Run: `mvn -q -Dtest=EmbeddingIndexTest test`
Expected: 3 个 PASS。

---

### Task 5: 索引任务、启动加载与状态接口

**Files:**
- Create: `QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/service/EmbeddingJobService.java`
- Create: `QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/service/impl/EmbeddingJobServiceImpl.java`
- Modify: `QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/controller/MemoryController.java`
- Test: `QQMessageAssistantJava/src/test/java/com/czx/qqmessageassistantjava/service/EmbeddingJobServiceTest.java`

**Interfaces:**
- Produces:
  - `int indexMissing(int limit)`
  - `int rebuild()`
  - `EmbeddingStatus status()`，`record EmbeddingStatus(boolean semantic, int indexed, long lag)`
  - `void loadIndex()`
  - `POST /api/memory/embeddings/rebuild`、`GET /api/memory/embeddings/status`

- [ ] **Step 1: 写失败测试（用 fake EmbeddingService）**

```java
@Test void indexMissing_writes_vectors_and_is_idempotent() {
    insertIn(9_910_001L, "小明生日11月11");
    int n1 = service.indexMissing(10);
    int n2 = service.indexMissing(10);
    assertThat(n1).isEqualTo(1);
    assertThat(n2).isEqualTo(0);
    assertThat(embeddingMapper.countByModel("fake")).isEqualTo(1);
}

@Test void status_reports_lag() { /* 插入 2 条，索引 1 条，lag==1 */ }

@Test void rebuild_clears_and_reindexes() { /* 先索引，再 rebuild，indexed 数不变 */ }
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -Dtest=EmbeddingJobServiceTest test`
Expected: 编译失败。

- [ ] **Step 3: 实现**

- `indexMissing`：`selectMissing(model, limit)` → `embedBatch` → `upsert` → `index.append`。
- 定时：`@Scheduled(fixedDelay = 60_000)` 调 `indexMissing(64)`；`semantic=false` 时直接返回 0。
- `status.lag` = `SELECT COUNT(*)` 同 `selectMissing` 但不限 LIMIT。
- `loadIndex`：`@EventListener(ApplicationReadyEvent.class)`；模型不可用则不加载并记日志；可用时先 `index.reset()`，再用 `selectAll(model, handler)` 逐行 `append`（不把全表读进 `List`）。
- 控制器：`@PostMapping("/embeddings/rebuild")` 返回重算行数；`@GetMapping("/embeddings/status")` 返回 `EmbeddingStatus`。
- `rebuild`：`deleteByModel` → 循环 `indexMissing(500)` 直到 0 → `loadIndex()`。

- [ ] **Step 4: 运行通过**

Run: `mvn -q -Dtest=EmbeddingJobServiceTest test`
Expected: 3 个 PASS。

---

### Task 6: RRF 融合与时间软分段

**Files:**
- Create: `QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/retrieval/Channel.java`
- Create: `QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/retrieval/Candidate.java`
- Create: `QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/retrieval/RetrievalResult.java`
- Create: `QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/retrieval/RrfFusion.java`
- Test: `QQMessageAssistantJava/src/test/java/com/czx/qqmessageassistantjava/retrieval/RrfFusionTest.java`

**Interfaces:**
- `enum Channel { NOTE, MSG_PERSON, MSG_MENTION, MSG_LEXICAL, MSG_SEMANTIC, MEMO, FILE }`，带 `String key()` 返回 `note/msg-person/...`。
- `record Candidate(String type, long id, String label, String content, LocalDateTime at, String source, String quote)`，`String key()` = `type+":"+id`。
- `record RetrievalResult(Map<Channel, List<Candidate>> hits)`。
- Produces: `static List<Candidate> fuse(RetrievalResult result, int charBudget, int maxItems, LocalDateTime from, LocalDateTime to)`。

- [ ] **Step 1: 写失败测试**

```java
@Test void rrf_prefers_item_hit_by_multiple_channels() {
    var a = cand("msg",1); var b = cand("msg",2);
    var r = new RetrievalResult(Map.of(
        Channel.MSG_LEXICAL, List.of(a),
        Channel.MSG_SEMANTIC, List.of(a, b)));
    assertThat(RrfFusion.fuse(r, 30_000, 150, null, null))
        .extracting(Candidate::key).containsExactly("msg:1", "msg:2");
}

@Test void time_range_puts_in_range_first_without_dropping_others() {
    var old = candAt("msg",1, LocalDateTime.of(2024,1,1,0,0));
    var recent = candAt("msg",2, LocalDateTime.of(2026,10,1,0,0));
    var r = new RetrievalResult(Map.of(Channel.MSG_LEXICAL, List.of(old, recent)));
    assertThat(RrfFusion.fuse(r, 30_000, 150, LocalDateTime.of(2026,1,1,0,0), null))
        .extracting(Candidate::key).containsExactly("msg:2", "msg:1");
}

@Test void char_budget_truncates_but_keeps_semantic_top10() { /* 构造 60 条，预算很小，断言语义前 10 都在 */ }
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -Dtest=RrfFusionTest test`
Expected: 编译失败。

- [ ] **Step 3: 实现**

RRF：每个通道内下标即名次，`score += 1.0/(60+rank+1)`；按 key 去重取最高分；
时间软分段在排序前做；预算按 `label.length()+content.length()+16` 累加；
语义与词法的前 10 条在截断后仍缺失时追加进结果（保底）。

- [ ] **Step 4: 运行通过**

Run: `mvn -q -Dtest=RrfFusionTest test`
Expected: 3 个 PASS。

---

### Task 7: 七路召回

**Files:**
- Create: `QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/retrieval/QueryPlan.java`
- Create: `QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/retrieval/RetrievalService.java`
- Create: `QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/retrieval/RetrievalServiceImpl.java`
- Test: `QQMessageAssistantJava/src/test/java/com/czx/qqmessageassistantjava/retrieval/RetrievalServiceTest.java`

**Interfaces:**
- `record QueryPlan(Set<Long> subjects, List<String> entities, List<String> keywords, List<String> queries, LocalDateTime timeFrom, LocalDateTime timeTo)`
- Produces: `RetrievalResult retrieve(QueryPlan plan, Set<String> excludeKeys)`
- Consumes: Task 1 的 Mapper、Task 4 的 `EmbeddingIndex`、Task 3 的 `EmbeddingService`、Task 6 的 `Candidate/Channel`。

- [ ] **Step 1: 写失败测试**

```java
@Test void mention_channel_finds_message_in_another_chat() {
    insertIn(chat=小红私聊, sender=小红, "小明生日是11月11");
    var plan = new QueryPlan(Set.of(), List.of("小明"), List.of("生日"), List.of("小明生日"), null, null);
    var r = service.retrieve(plan, Set.of());
    assertThat(r.hits().get(Channel.MSG_MENTION))
        .extracting(Candidate::key).contains("msg:" + id);
    assertThat(r.hits().get(Channel.MSG_MENTION).get(0).label()).contains("QQ");
}

@Test void semantic_channel_is_null_when_unavailable() {
    // fake EmbeddingService.available()=false
    assertThat(service.retrieve(plan, Set.of()).hits()).doesNotContainKey(Channel.MSG_SEMANTIC);
}

@Test void semantic_channel_finds_paraphrase() {
    // fake 向量：把「他 11 号生日」与问题向量置近，断言 MSG_SEMANTIC 命中该条
}

@Test void semantic_query_uses_average_of_first_three_queries() {
    // 构造：q1、q2、q3 各自都不命中，三条的平均向量才命中——验证取平均而不是只用 q1
}

@Test void old_message_beyond_thirty_is_retrieved() {
    // 同一 sender 塞 40 条，目标消息排在第 35 条，断言 msg-person 与 msg-lexical 仍命中
}

@Test void long_message_beyond_512_tokens_is_still_found_lexically() {
    // content 长于 512 token，断言 msg-lexical 命中，且语义通道不抛异常
}

@Test void failing_channel_reports_null_and_others_continue() {
    // 让 note 查询抛异常（Mockito spy），断言 retrieve 不抛、hits 无 NOTE、其余通道仍有结果
}

@Test void blank_content_is_skipped() { /* content=null/"" 不抛异常、不进语义 */ }

@Test void exclude_keys_are_not_returned() { /* 第二轮排除已见 id */ }
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -Dtest=RetrievalServiceTest test`
Expected: 编译失败。

- [ ] **Step 3: 实现**

按 `design/intelligent_retrieval.md` §7.3 的 8 行 → 7 个通道：
`note`（主体 + 关键词 LIKE，去重合并）、`msg-person`、`msg-mention`、`msg-lexical`（每条 query 各 100）、
`msg-semantic`（前 3 条 query 的平均向量，top 100）、`memo`、`file`。
每路 try/catch，失败记 `log.warn` 并从 `hits` 中省略（上游把缺失键记为 `null`）；
`excludeKeys` 在放进结果前过滤。`msg-semantic` 需要 `message_log` 补查：先由 index 拿 id，再 `selectBatchIds`。

- [ ] **Step 4: 运行通过**

Run: `mvn -q -Dtest=RetrievalServiceTest test`
Expected: 4 个 PASS。

---

### Task 8: Ask 单轮编排（resolve、认人、终态表、scope、证据）

**Files:**
- Modify: `QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/service/impl/AskServiceImpl.java`（重写主体）
- Modify: `QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/vo/AskResponse.java`
- Create: `QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/vo/AskScopeVO.java`
- Modify: `QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/vo/EvidenceVO.java`
- Modify: `QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/vo/ClarifyStateVO.java`
- Test: `QQMessageAssistantJava/src/test/java/com/czx/qqmessageassistantjava/service/AskSingleRoundTest.java`

**Interfaces:**
- `AskScopeVO{Boolean semantic; Integer lag; Integer rounds; Integer candidateCnt; Map<String,Integer> channels; String message}`
- `EvidenceVO` 增加 `String source`、`String quote`
- `ClarifyStateVO` 增加 `Integer occurrences`
- Consumes: Task 7 的 `RetrievalService`、Task 5 的 `EmbeddingJobService`、Task 1 的 `AskTraceMapper`

- [ ] **Step 1: 写失败测试（mock DifyWorkflowClient）**

```java
@Test void cross_chat_answer_is_found_and_cites_source() {
    insertIn(9_920_001L, chat 小红私聊, sender 小红, "小明生日是11月11");
    when(dify.run("ask", argThat(i -> "resolve".equals(i.get("stage")))))
        .thenReturn(Map.of("entities","[\"小明\"]","keywords","[\"生日\"]","queries","[\"小明生日\"]",
                           "time_hint","","time_from","","time_to",""));
    when(dify.run("ask", argThat(i -> "answer".equals(i.get("stage")))))
        .thenReturn(Map.of("answer","小明的生日是11月11日","used_ids","[\"msg:9_920_001\"]"));
    AskResponse r = service.ask(req("小明的生日是什么时候"));
    assertThat(r.getAnswer()).contains("11月11");
    assertThat(r.getEvidence().get(0).getSource()).contains("QQ");
    assertThat(r.getEvidence().get(0).getQuote()).contains("小明生日");
}

@Test void ambiguous_alias_asks_before_retrieving() { /* contact 两行同名 → needClarify，scope.channels 全 null */ }

@Test void unknown_alias_with_occurrences_asks_with_count() { /* occurrences=3 */ }

@Test void unknown_alias_without_occurrences_is_not_found() { /* needClarify=null */ }

@Test void invalid_used_ids_degrades_to_evidence() { /* degraded=true */ }

@Test void answer_without_answer_and_with_candidates_reports_message() { /* 终态第 6 条 */ }

@Test void no_candidates_reports_not_found() { /* 终态第 7 条；answer=""，needClarify=null，scope.message 非空 */ }

@Test void unknown_alias_can_still_be_answered() {
    // 「阿呆」在记录里出现过、contact 里没有；模型给出结论 → answer 非空，
    // scope.message 注明「未登记的联系人，按名字检索」
}

@Test void ambiguous_alias_takes_priority_over_a_unique_one() {
    // entities=["小明","阿黄"]，阿黄对应两个 sender，小明唯一 → needClarify(阿黄)，answer 为空
}

@Test void resolve_failure_falls_back_to_the_raw_question() {
    when(dify.run("ask", argThat(i -> "resolve".equals(i.get("stage")))))
        .thenThrow(new BusinessException("resolve 挂了"));
    // 断言不抛 500、仍执行 answer 调用，且 answer 的 candidates 来自原问题检索
}

@Test void answer_failure_degrades_without_500() { /* answer 抛错 → degraded=true，只回证据 */ }

@Test void new_messages_are_indexed_before_retrieval_and_lag_is_reported() {
    // 5 条未索引 + 补跑上限 3 → 请求内补跑 3 条，scope.lag==2
}

@Test void scope_always_has_the_seven_channel_keys() {
    // 语义不可用时 channels 有且仅有 7 个键，msg-semantic 为 null，其余为 0 或计数
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -Dtest=AskSingleRoundTest test`
Expected: 编译失败或断言失败。

- [ ] **Step 3: 实现**

流程严格按 `design/intelligent_retrieval.md` §7.0–§7.5、§7.7：
1. `clarify` 落库；2. resolve（失败则原问题当 query）；3. 认人（≥2 反问；0 条记 unknownAliases）；
4. 未知 alias 一律算 `occurrences`；5. 检索前 `indexMissing(500)` 补跑，剩余条数写 `scope.lag`；
6. 单轮 `retrieve`（本轮不做多轮，第 9 个任务加）；7. 融合后答题（answer 调用失败按降级走，不抛 500）；
8. 证据校验；9. 按 §7.7 表生成终态（第 1 条优先级最高）；10. 组装 `scope`（7 个键恒在，未跑/失败为 `null`）；
11. `note` 证据批量补 source/quote；12. 写 `ask_trace`。

- [ ] **Step 4: 运行通过**

Run: `mvn -q -Dtest=AskSingleRoundTest test`
Expected: 6 个 PASS。

---

### Task 9: 多轮 expand、跨轮累计与 trace

**Files:**
- Modify: `QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/service/impl/AskServiceImpl.java`
- Modify: `QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/retrieval/RetrievalResult.java`（加 `merge`）
- Test: `QQMessageAssistantJava/src/test/java/com/czx/qqmessageassistantjava/service/AskMultiRoundTest.java`

**Interfaces:**
- `RetrievalResult.merge(RetrievalResult other)`：按 key 去重，保留各通道最好名次（即按通道追加时跳过已存在 key）。
- Consumes: Task 7、Task 8。

- [ ] **Step 1: 写失败测试**

```java
@Test void two_hop_uses_round_one_fact_in_round_two_answer() {
    // 轮1：A 会话「小明跟学霸住一屋」；expand 返回 entities=["学霸"]
    // 轮2：B 会话「学霸叫李四」；answer 同时引用 msg:A 与 msg:B 的 id
    assertThat(r.getAnswer()).contains("李四");
    assertThat(r.getScope().getRounds()).isEqualTo(2);
}

@Test void round_two_may_cite_round_one_id() { /* 不判越界 */ }

@Test void stops_after_three_rounds_and_says_not_found() { /* rounds==3，answer 空 */ }

@Test void empty_candidates_still_expand_once() { /* 第 1 轮 0 候选 → 仍调 expand；expand 返回空才停止 */ }

@Test void ask_trace_has_one_row_with_counts_and_channels() {
    // 一次问答后 ask_trace 恰好 1 行；rounds / candidate_cnt / channels 与 scope 一致
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -Dtest=AskMultiRoundTest test`
Expected: 失败。

- [ ] **Step 3: 实现**

`while (round < 3)`：检索 → `merge` → 答题 → 有效则停 → 否则 `stage=expand`；
expand 返回空则停；累计候选在最后一次答题前才做预算截断；
`scope.channels` 逐轮累加；`candidate_cnt` 取累计去重数。

- [ ] **Step 4: 运行通过**

Run: `mvn -q -Dtest=AskMultiRoundTest test`
Expected: 3 个 PASS。

---

### Task 10: 归因修复

**Files:**
- Modify: `QQMessageAssistantJava/src/main/java/com/czx/qqmessageassistantjava/service/impl/MemoryJobServiceImpl.java`
- Test: `QQMessageAssistantJava/src/test/java/com/czx/qqmessageassistantjava/service/MemoryAttributionTest.java`

**Interfaces:**
- 修改 `write(...)` 的入参，改为携带本批 `List<MessageLog>` 以便校验正文。
- Produces: `static boolean aliasAppearsIn(String alias, String content)`（包内可见，供测试）。

- [ ] **Step 1: 写失败测试**

```java
@Test void third_party_fact_is_attributed_when_alias_in_message() {
    // 小红发「小明生日11月11」，known_contacts 有 小明→123
    // note subject=123 → 写入
    assertThat(countNotes(123L)).isEqualTo(1);
}

@Test void third_party_fact_is_dropped_when_alias_absent() {
    // 消息里没有「小明」→ 整条丢弃
}

@Test void subject_not_in_batch_or_contacts_is_dropped() { /* subject=999 */ }
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -Dtest=MemoryAttributionTest test`
Expected: 失败。

- [ ] **Step 3: 实现**

按 spec §6：主体 ∈ 本批发言人 ∪ DB `known_contacts` 的 senderId 集合；
非本批发言人时，要求该主体的任一 alias 在证据消息正文中出现（大小写不敏感 substring）；
否则整条丢弃并 `log.info`。不动主人（subject=null）的路径。

- [ ] **Step 4: 运行通过**

Run: `mvn -q -Dtest=MemoryAttributionTest test`
Expected: 3 个 PASS。

---

### Task 11: Dify 工作流 DSL

**Files:**
- Modify: `design/QQAssistant-memory-workflow.yml`
- Test: `QQMessageAssistantJava/src/test/java/com/czx/qqmessageassistantjava/dify/MemoryWorkflowDslTest.java`（读 `../design/QQAssistant-memory-workflow.yml`）

**Interfaces:**
- `resolve_llm` 输出 `queries` / `time_from` / `time_to`；`parse_resolve` 返回同名键。
- 新增 `is_expand` 分支、`expand_llm`、`parse_expand`，输出 `queries/entities/time_hint/time_from/time_to`。

- [ ] **Step 1: 写 DSL 静态测试**

用 Spring 自带的 SnakeYAML 读 YAML：断言存在 `expand_llm`、`parse_expand` 节点；
`is_expand` 的条件引用 `stage`；`parse_resolve` 的 outputs 含 `queries`、`time_from`、`time_to`；
`answer_llm` 的提示词包含 `msg-mention`。

- [ ] **Step 2: 运行确认失败**

Run: `mvn -q -Dtest=MemoryWorkflowDslTest test`
Expected: 断言失败。

- [ ] **Step 3: 改 YAML**

在 Dify 控制台改节点、导出、覆盖仓库文件；或在 YAML 里按现有节点结构复制改写后导入验证。
提示词只加语义要求，不写检索阈值/RRF/校验规则。

- [ ] **Step 4: 运行通过**

Run: `mvn -q -Dtest=MemoryWorkflowDslTest test`
Expected: PASS。

---

### Task 12: 前端

**Files:**
- Modify: `QQMessageAssistantVue/src/api/ask.ts`
- Modify: `QQMessageAssistantVue/src/views/AskView.vue`
- Test: `cd QQMessageAssistantVue && npm run build`

- [ ] **Step 1: 改类型**

`Evidence` 加 `source?: string`、`quote?: string`；`ClarifyState` 加 `occurrences: number`；
新增 `AskScope { semantic: boolean; lag: number; rounds: number; candidateCnt: number;
channels: Record<string, number | null>; message: string }`；`AskResponse` 加 `scope?: AskScope`。

- [ ] **Step 2: 改界面**

- 证据卡显示 `source`，`quote` 默认折叠、点击展开；
- `scope.semantic === false` → 顶部提示；`lag > 0` → 「有 N 条新消息尚未建立语义索引」；
- 找不到/答不出 → 显示 `scope.message` 与通道计数（`null` 不显示）；
- 反问区显示 `occurrences`。

- [ ] **Step 3: 构建**

Run: `cd QQMessageAssistantVue && npm run build`
Expected: `vue-tsc` 与 `vite build` 均无错误。

---

### Task 13: 文档同步

**Files:**
- Modify: `design/core_features.md`（§7.2 结论改为混合检索；`:434` 标注已推翻）
- Modify: `design/memory_workflow.md`（§1.2 / §4.2 归因规则）
- Modify: `design/api_contract.md`（`/api/ask` 的 `scope`、`source`、`quote`、`occurrences`；两个 embedding 端点）
- Modify: `design/database_schema.md`（两张表 + FULLTEXT）
- Modify: `design/architecture.md`（检索职责）
- Modify: `design/intelligent_retrieval.md`（§7.8 示例键 `candidate_cnt` → `candidateCnt`）
- Modify: `deploy/README.md`（模型下载步骤）
- Test: 无（文档改动，靠 Task 14 的最终检查）

- [ ] **Step 1: 按 `design/intelligent_retrieval.md` §14 逐条修改**

- [ ] **Step 2: 全文搜索确认没有残留「不做向量检索」的旧结论**

Run: `grep -rn "不做向量" design/`
Expected: 只在 `intelligent_retrieval.md` 的推翻说明与 `core_features.md` 的「已推翻」标注里出现。

---

### Task 14: 全量验证与真机

**Files:** 无新增。

- [ ] **Step 1: 后端全量**

Run: `cd QQMessageAssistantJava && mvn -q test`
Expected: 全部 PASS，明确记录总数；任何失败都要贴输出。

- [ ] **Step 2: 前端构建**

Run: `cd QQMessageAssistantVue && npm run build`
Expected: 无错误。

- [ ] **Step 3: 插件回归（未改动，确认没被带坏）**

Run: `cd QQMessageAssistantPython/qq_assistant_plugin && ../.venv/Scripts/python.exe -m pytest tests -q`
Expected: 与改动前的基线通过数一致（先记录改动前的数再比，不预设数字）。

- [ ] **Step 4: 真机**

1. `bash scripts/fetch-embedding-model.sh` → 文件大小正确；
2. 启动后端 → `GET /api/memory/embeddings/status` 返回 `semantic=true`；
3. 灌入 3 个会话的消息（含跨会话提到同一个人）→ `/api/ask` 问该人属性 → 答案与 `source` 对得上；
4. 改错 `model-dir` 重启 → `semantic=false`，问答仍可用，前端有提示；
5. `POST /api/memory/embeddings/rebuild` → 行数不变；
6. 在 Dify 控制台导入并发布新 DSL 后再跑一次 3。
7. `POST /api/memory/rebuild`（事实重抽，不是 embeddings/rebuild）按新归因规则重跑历史；
   这一步会消耗 Dify token，由使用者决定何时执行，不进夜间自动任务。

- [ ] **Step 5: 把 Task 14 的实际输出写进交付报告**

不写「应该通过」，只写跑过的命令与真实结果；跳过的项单独列出来。

---

## 风险与未决

| 项 | 处理 |
|---|---|
| Dify 控制台改 DSL 无法由本计划自动完成 | Task 11 只保证仓库里的 YAML 与静态测试；真机前必须由使用者导入发布 |
| 历史重抽消耗 token | `POST /api/memory/rebuild` 是手动步骤，不自动跑 |
| 50 万条以上暴力余弦变慢 | 本期不做 ANN；`status` 与启动日志暴露规模，超限再开新任务 |
| 模型下载受网络影响 | 脚本从 `hf-mirror.com` 取，失败即退出；`mode=http` 是备选 |
