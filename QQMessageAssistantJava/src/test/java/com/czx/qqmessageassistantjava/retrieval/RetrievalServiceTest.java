package com.czx.qqmessageassistantjava.retrieval;

import com.czx.qqmessageassistantjava.embedding.EmbeddingIndex;
import com.czx.qqmessageassistantjava.embedding.EmbeddingService;
import com.czx.qqmessageassistantjava.entity.FileTask;
import com.czx.qqmessageassistantjava.entity.Memo;
import com.czx.qqmessageassistantjava.entity.MemoryNote;
import com.czx.qqmessageassistantjava.entity.MessageLog;
import com.czx.qqmessageassistantjava.mapper.FileTaskMapper;
import com.czx.qqmessageassistantjava.mapper.MemoMapper;
import com.czx.qqmessageassistantjava.mapper.MemoryNoteMapper;
import com.czx.qqmessageassistantjava.mapper.MessageLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 七路召回的集成测试。
 *
 * <p>除了通道命中本身，这里钉住四件容易退化的行为：
 * <ul>
 *   <li>{@code msg-mention} 跨会话命中，且标签带「QQ」；</li>
 *   <li>语义服务不可用时该通道<b>缺失</b>，而不是空列表（null 与 0 的区别）；</li>
 *   <li>语义查询向量取前 3 条 query 的<b>平均</b>，不是只用第一条；</li>
 *   <li>单路抛异常只丢掉该路，其余通道继续。</li>
 * </ul>
 *
 * <p>使用 {@code MATCH…AGAINST} 的用例必须 {@link Propagation#NOT_SUPPORTED}：
 * InnoDB FULLTEXT 看不到测试事务里尚未提交的行。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RetrievalServiceTest {

    private static final int DIM = 512;
    private static final LocalDateTime AT = LocalDateTime.of(2026, 11, 11, 20, 0);

    @TestConfiguration
    static class FakeEmbeddingConfig {

        @Bean
        @Primary
        FakeEmbeddingService fakeEmbeddingService() {
            return new FakeEmbeddingService();
        }
    }

    static final class FakeEmbeddingService implements EmbeddingService {

        private volatile boolean available;
        private final Map<String, float[]> vectors = new ConcurrentHashMap<>();
        private final AtomicInteger embedCalls = new AtomicInteger();

        void setAvailable(boolean available) {
            this.available = available;
        }

        int embedCalls() {
            return embedCalls.get();
        }

        void resetEmbedCalls() {
            embedCalls.set(0);
        }

        void put(String text, float[] vector) {
            if (vector == null || vector.length != DIM) {
                throw new IllegalArgumentException("fake vector must be " + DIM + " dims");
            }
            vectors.put(text, vector);
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public String modelId() {
            return "fake";
        }

        @Override
        public int dim() {
            return DIM;
        }

        @Override
        public float[] embed(String text) {
            embedCalls.incrementAndGet();
            if (!available) {
                throw new IllegalStateException("fake embedding unavailable");
            }
            float[] mapped = vectors.get(text);
            return mapped != null ? mapped : fallback(text);
        }

        @Override
        public List<float[]> embedBatch(List<String> texts) {
            return texts.stream().map(this::embed).toList();
        }

        /** 未显式映射的文本得到确定性的单位向量，避免意外命中测试构造的坐标轴。 */
        private static float[] fallback(String text) {
            float[] vector = new float[DIM];
            int seed = text == null ? 0 : text.hashCode();
            double sum = 0;
            for (int i = 0; i < DIM; i++) {
                vector[i] = (float) Math.sin((i + 1) * 0.01 * (Math.floorMod(seed, 1009) + 1));
                sum += vector[i] * vector[i];
            }
            float scale = (float) (1.0 / Math.sqrt(sum));
            for (int i = 0; i < DIM; i++) {
                vector[i] *= scale;
            }
            return vector;
        }
    }

    @Autowired
    private RetrievalService service;

    @Autowired
    private FakeEmbeddingService fake;

    @Autowired
    private EmbeddingIndex index;

    @Autowired
    private MessageLogMapper messageLogMapper;

    /** 语义可用性的唯一来源；默认「模型在且索引已加载」。 */
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.czx.qqmessageassistantjava.service.EmbeddingJobService embeddingJobService;

    @Autowired
    private MemoMapper memoMapper;

    @Autowired
    private MemoryNoteMapper memoryNoteMapper;

    @Autowired
    private FileTaskMapper fileTaskMapper;

    @BeforeEach
    void resetState() {
        index.reset();
        fake.setAvailable(false);
        fake.resetEmbedCalls();
        when(embeddingJobService.status())
                .thenReturn(new com.czx.qqmessageassistantjava.service.EmbeddingJobService.EmbeddingStatus(
                        true, 0, 0));
    }

    /**
     * spec §3.1：机器人自己发出的 OUT 不是证据。词法/提及两路共用全文检索，
     * 少了 direction 过滤就会把机器人说过的话当成对方说的，证据卡上还会署上对方的名字。
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void bot_sent_message_is_not_evidence() {
        long id = 9_930_601L;
        messageLogMapper.deleteById(id);
        try {
            insertMessage(id, null, "小红私聊", 201L, "小红",
                    "机器人这句独有关键词丙丁戊", AT, "OUT");

            var plan = new QueryPlan(Set.of(), List.of(), List.of(),
                    List.of("独有关键词丙丁戊"), null, null);


            var result = service.retrieve(plan, Set.of());

            assertThat(result.hits().get(Channel.MSG_LEXICAL))
                    .extracting(Candidate::key).doesNotContain("msg:" + id);
        } finally {
            messageLogMapper.deleteById(id);
        }
    }

    /**
     * 语义通道跑没跑，必须和 {@code scope.semantic} 用同一个判据（spec §7.8）。
     * 模型在、索引没加载时，该通道应缺席（null），而不是报 0。
     */
    @Test
    void semantic_channel_is_absent_when_scope_says_semantic_is_not_ready() {
        fake.setAvailable(true);
        when(embeddingJobService.status())
                .thenReturn(new com.czx.qqmessageassistantjava.service.EmbeddingJobService.EmbeddingStatus(
                        false, 0, 0));
        var plan = new QueryPlan(Set.of(), List.of(), List.of("随便问问"),
                List.of("随便问问"), null, null);

        var result = service.retrieve(plan, Set.of());

        assertThat(result.hits()).doesNotContainKey(Channel.MSG_SEMANTIC);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void mention_channel_finds_message_in_another_chat() {
        long id = 9_930_101L;
        messageLogMapper.deleteById(id);
        try {
            insertMessage(id, null, "小红私聊", 201L, "小红", "小明生日是11月11", AT);

            var plan = new QueryPlan(Set.of(), List.of("小明"), List.of("生日"),
                    List.of("小明生日"), null, null);

            var result = service.retrieve(plan, Set.of());

            List<Candidate> mention = result.hits().get(Channel.MSG_MENTION);
            assertThat(mention).isNotNull();
            assertThat(mention).extracting(Candidate::key).contains("msg:" + id);
            assertThat(mention.get(0).label()).contains("QQ");
        } finally {
            messageLogMapper.deleteById(id);
        }
    }

    @Test
    void semantic_channel_is_null_when_unavailable() {
        fake.setAvailable(false);

        var plan = new QueryPlan(Set.of(), List.of(), List.of(), List.of("随便问一句"), null, null);

        var result = service.retrieve(plan, Set.of());

        assertThat(result.hits()).doesNotContainKey(Channel.MSG_SEMANTIC);
    }

    @Test
    void semantic_channel_finds_paraphrase() {
        long id = 9_930_201L;
        insertMessage(id, 9_930_200L, "三班群", 202L, "小红", "他 11 号生日", AT);

        fake.setAvailable(true);
        fake.put("小明生日", basis(0));
        index.append(id, basis(0));

        var plan = new QueryPlan(Set.of(), List.of("小明"), List.of(), List.of("小明生日"), null, null);

        var result = service.retrieve(plan, Set.of());

        assertThat(result.hits().get(Channel.MSG_SEMANTIC))
                .extracting(Candidate::key).contains("msg:" + id);
    }

    @Test
    void semantic_query_uses_average_of_first_three_queries() {
        long distractor = 9_930_301L;
        long target = 9_930_302L;
        insertMessage(distractor, 9_930_300L, "测试群", 203L, "小明", "无关的干扰消息", AT);
        insertMessage(target, 9_930_300L, "测试群", 204L, "小红", "真正答案在这里", AT);

        // 干扰项正好是 e0；目标是 [1,1,1]/√3。三条 query 的平均向量与目标重合、与干扰项有夹角。
        index.append(distractor, basis(0));
        index.append(target, normalized(1, 1, 1));

        fake.setAvailable(true);
        fake.put("q1", basis(0));
        fake.put("q2", basis(1));
        fake.put("q3", basis(2));
        fake.put("q4", basis(3));

        var plan = new QueryPlan(Set.of(), List.of(), List.of(),
                List.of("q1", "q2", "q3", "q4"), null, null);

        var result = service.retrieve(plan, Set.of());

        List<String> keys = result.hits().get(Channel.MSG_SEMANTIC).stream()
                .map(Candidate::key).toList();
        assertThat(keys).isNotEmpty();
        assertThat(keys.get(0)).isEqualTo("msg:" + target);
        // 第 4 条 query 不参与语义通道：只 embed 前 3 条
        assertThat(fake.embedCalls()).isEqualTo(3);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void old_message_beyond_thirty_is_retrieved() {
        long target = 9_930_400L;
        long sender = 301L;
        try {
            insertMessage(target, 9_930_399L, "老群", sender, "老王",
                    "旧消息关键词甲乙很重要", LocalDateTime.of(2026, 1, 1, 0, 0));
            for (int i = 1; i < 40; i++) {
                insertMessage(target + i, 9_930_399L, "老群", sender, "老王",
                        "普通消息编号" + i, LocalDateTime.of(2026, 1, 1, 0, 0).plusMinutes(i));
            }

            var plan = new QueryPlan(Set.of(sender), List.of(), List.of(),
                    List.of("旧消息关键词甲乙"), null, null);

            var result = service.retrieve(plan, Set.of());

            assertThat(result.hits().get(Channel.MSG_PERSON))
                    .extracting(Candidate::key).contains("msg:" + target);
            assertThat(result.hits().get(Channel.MSG_LEXICAL))
                    .extracting(Candidate::key).contains("msg:" + target);
        } finally {
            for (int i = 0; i < 40; i++) {
                messageLogMapper.deleteById(target + i);
            }
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void long_message_beyond_512_tokens_is_still_found_lexically() {
        long id = 9_930_501L;
        messageLogMapper.deleteById(id);
        try {
            String content = "超长消息" + "很长".repeat(1000);
            insertMessage(id, 9_930_500L, "长文群", 302L, "小红", content, AT);

            fake.setAvailable(true);
            fake.put("超长消息", basis(0));

            var plan = new QueryPlan(Set.of(), List.of(), List.of(),
                    List.of("超长消息"), null, null);

            var result = service.retrieve(plan, Set.of());

            assertThat(result.hits().get(Channel.MSG_LEXICAL))
                    .extracting(Candidate::key).contains("msg:" + id);
            assertThat(result.hits()).containsKey(Channel.MSG_SEMANTIC);
        } finally {
            messageLogMapper.deleteById(id);
        }
    }

    @Test
    void failing_channel_reports_null_and_others_continue() {
        MemoryNoteMapper brokenNoteMapper = mock(MemoryNoteMapper.class);
        doThrow(new RuntimeException("note query boom"))
                .when(brokenNoteMapper).searchByKeywords(anyList(), anyInt());
        RetrievalService broken = new RetrievalServiceImpl(
                brokenNoteMapper, messageLogMapper, memoMapper, fileTaskMapper, fake, index,
                embeddingJobService);

        Memo memo = new Memo();
        memo.setSenderId(401L);
        memo.setTitle("生日提醒");
        memo.setContent("买蛋糕");
        memo.setImportance(3);
        memo.setStatus("ACTIVE");
        memoMapper.insert(memo);
        try {
            var plan = new QueryPlan(Set.of(), List.of(), List.of("生日"), List.of(), null, null);

            var result = broken.retrieve(plan, Set.of());

            assertThat(result.hits()).doesNotContainKey(Channel.NOTE);
            assertThat(result.hits().get(Channel.MEMO)).isNotEmpty();
        } finally {
            memoMapper.deleteById(memo.getId());
        }
    }

    @Test
    void blank_content_is_skipped() {
        long empty = 9_930_601L;
        long nullContent = 9_930_602L;
        long good = 9_930_603L;
        insertMessage(empty, 9_930_600L, "测试群", 402L, "小明", "", AT);
        insertMessage(nullContent, 9_930_600L, "测试群", 403L, "小明", null, AT);
        insertMessage(good, 9_930_600L, "测试群", 404L, "小明", "有内容的消息", AT);

        index.append(empty, basis(0));
        index.append(nullContent, basis(0));
        index.append(good, basis(0));
        fake.setAvailable(true);
        fake.put("空白测试", basis(0));

        var plan = new QueryPlan(Set.of(), List.of(), List.of(), List.of("空白测试"), null, null);

        var result = service.retrieve(plan, Set.of());

        assertThat(result.hits().get(Channel.MSG_SEMANTIC))
                .extracting(Candidate::key)
                .contains("msg:" + good)
                .doesNotContain("msg:" + empty, "msg:" + nullContent);
    }

    @Test
    void exclude_keys_are_not_returned() {
        long seen = 9_930_701L;
        long fresh = 9_930_702L;
        insertMessage(seen, 9_930_700L, "测试群", 701L, "小明", "第一轮已经见过", AT);
        insertMessage(fresh, 9_930_700L, "测试群", 701L, "小明", "第二轮新命中", AT.plusMinutes(1));

        var plan = new QueryPlan(Set.of(701L), List.of(), List.of(), List.of(), null, null);

        var result = service.retrieve(plan, Set.of("msg:" + seen));

        assertThat(result.hits().get(Channel.MSG_PERSON))
                .extracting(Candidate::key)
                .contains("msg:" + fresh)
                .doesNotContain("msg:" + seen);
    }

    /**
     * 第二轮会把第一轮见过的 key 全部排除。若先按 SQL LIMIT 取最新 30 条再过滤，
     * 这 30 条恰好都被排除时该路会「跑了但为空」，更旧的未命中候选永远拿不到。
     * file 通道上限 30：塞 35 条、排除最新的 30 条，最旧的 5 条必须仍在。
     */
    @Test
    void exclude_keys_beyond_channel_limit_are_still_retrieved() {
        List<Long> ids = new ArrayList<>();
        try {
            for (int i = 0; i < 35; i++) {
                FileTask file = new FileTask();
                file.setTaskRef("retrieval-overfetch-" + i);
                file.setSenderId(603L);
                file.setFileName("批量文件关键词-" + i + ".xlsx");
                file.setStatus("PENDING");
                file.setCreatedAt(AT.plusMinutes(i));
                fileTaskMapper.insert(file);
                ids.add(file.getId());
            }

            Set<String> excluded = new HashSet<>();
            for (int i = 5; i < 35; i++) {
                excluded.add("file:" + ids.get(i));
            }

            var plan = new QueryPlan(Set.of(), List.of(), List.of("批量文件关键词"),
                    List.of(), null, null);

            var result = service.retrieve(plan, excluded);

            List<String> keys = result.hits().get(Channel.FILE).stream()
                    .map(Candidate::key).toList();
            assertThat(keys).hasSize(5);
            for (int i = 0; i < 5; i++) {
                assertThat(keys).contains("file:" + ids.get(i));
            }
        } finally {
            for (Long id : ids) {
                fileTaskMapper.deleteById(id);
            }
        }
    }

    /**
     * FULLTEXT 同分时原来没有 tie-breaker，返回顺序不确定，RRF 名次与证据顺序会抖。
     * 两条正文完全相同的消息，按 id 倒序应让后插入（id 大）的那条排在前面。
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void fulltext_ties_break_by_id_desc() {
        long lower = 9_930_801L;
        long higher = 9_930_802L;
        messageLogMapper.deleteById(lower);
        messageLogMapper.deleteById(higher);
        try {
            insertMessage(lower, 9_930_800L, "同分群", 801L, "小明", "同分测试内容", AT);
            insertMessage(higher, 9_930_800L, "同分群", 801L, "小明", "同分测试内容", AT);

            var plan = new QueryPlan(Set.of(), List.of(), List.of(),
                    List.of("同分测试内容"), null, null);

            var result = service.retrieve(plan, Set.of());

            assertThat(result.hits().get(Channel.MSG_LEXICAL))
                    .extracting(Candidate::key)
                    .containsExactly("msg:" + higher, "msg:" + lower);
        } finally {
            messageLogMapper.deleteById(lower);
            messageLogMapper.deleteById(higher);
        }
    }

    /**
     * 结构化通道按时间倒序（spec §7.4），不是按自增 id。
     * 先插「新」再插「旧」：id DESC 会把旧的排前面，只有 created_at DESC 能让新的第一。
     */
    @Test
    void structured_channels_order_by_created_at_desc() {
        Memo newerMemo = insertMemo("排序关键词备忘新", LocalDateTime.of(2026, 10, 2, 10, 0));
        Memo olderMemo = insertMemo("排序关键词备忘旧", LocalDateTime.of(2026, 10, 1, 10, 0));
        FileTask newerFile = insertFile("排序关键词文件新.xlsx", LocalDateTime.of(2026, 10, 2, 10, 0));
        FileTask olderFile = insertFile("排序关键词文件旧.xlsx", LocalDateTime.of(2026, 10, 1, 10, 0));
        MemoryNote newerNote = insertNote("排序关键词事实新", LocalDateTime.of(2026, 10, 2, 10, 0));
        MemoryNote olderNote = insertNote("排序关键词事实旧", LocalDateTime.of(2026, 10, 1, 10, 0));
        try {
            var plan = new QueryPlan(Set.of(), List.of(), List.of("排序关键词"),
                    List.of(), null, null);

            var result = service.retrieve(plan, Set.of());

            assertThat(result.hits().get(Channel.MEMO).get(0).key())
                    .isEqualTo("memo:" + newerMemo.getId());
            assertThat(result.hits().get(Channel.FILE).get(0).key())
                    .isEqualTo("file:" + newerFile.getId());
            assertThat(result.hits().get(Channel.NOTE).get(0).key())
                    .isEqualTo("note:" + newerNote.getId());
        } finally {
            memoMapper.deleteById(newerMemo.getId());
            memoMapper.deleteById(olderMemo.getId());
            fileTaskMapper.deleteById(newerFile.getId());
            fileTaskMapper.deleteById(olderFile.getId());
            memoryNoteMapper.deleteById(newerNote.getId());
            memoryNoteMapper.deleteById(olderNote.getId());
        }
    }

    /** 跑了的通道必须出现，即使输入为空、命中为 0；只有没跑的通道才缺席。 */
    @Test
    void empty_inputs_keep_channels_present_with_empty_lists() {
        fake.setAvailable(true);

        var plan = new QueryPlan(Set.of(), List.of(), List.of(), List.of(), null, null);

        var result = service.retrieve(plan, Set.of());

        assertThat(result.hits()).containsKeys(Channel.values());
        for (Channel channel : Channel.values()) {
            assertThat(result.hits().get(channel)).isEmpty();
        }
    }

    private Memo insertMemo(String title, LocalDateTime createdAt) {
        Memo memo = new Memo();
        memo.setSenderId(601L);
        memo.setTitle(title);
        memo.setContent(title);
        memo.setImportance(3);
        memo.setStatus("ACTIVE");
        memo.setCreatedAt(createdAt);
        memoMapper.insert(memo);
        return memo;
    }

    private FileTask insertFile(String fileName, LocalDateTime createdAt) {
        FileTask file = new FileTask();
        file.setTaskRef("retrieval-order-" + System.nanoTime());
        file.setSenderId(602L);
        file.setFileName(fileName);
        file.setStatus("PENDING");
        file.setCreatedAt(createdAt);
        fileTaskMapper.insert(file);
        return file;
    }

    private MemoryNote insertNote(String value, LocalDateTime createdAt) {
        MemoryNote note = new MemoryNote();
        note.setPredicate("测试");
        note.setValue(value);
        note.setSourceMsgIds("0");
        note.setCreatedAt(createdAt);
        memoryNoteMapper.insert(note);
        return note;
    }

    private void insertMessage(long id, Long chatId, String chatName, long senderId, String senderName,
                               String content, LocalDateTime createdAt) {
        insertMessage(id, chatId, chatName, senderId, senderName, content, createdAt, "IN");
    }

    private void insertMessage(long id, Long chatId, String chatName, long senderId, String senderName,
                               String content, LocalDateTime createdAt, String direction) {
        MessageLog row = new MessageLog();
        row.setId(id);
        row.setSenderId(senderId);
        row.setSenderName(senderName);
        row.setChatId(chatId);
        row.setChatName(chatName);
        row.setConversationId("conv-" + id);
        row.setMessageId("msg-" + id);
        row.setDedupeKey("retrieval-seed-" + id);
        row.setDirection(direction);
        row.setMsgType("text");
        row.setContent(content);
        row.setCreatedAt(createdAt);
        messageLogMapper.insert(row);
    }

    private static float[] basis(int axis) {
        float[] vector = new float[DIM];
        vector[axis] = 1f;
        return vector;
    }

    private static float[] normalized(float... head) {
        float[] vector = new float[DIM];
        double sum = 0;
        for (int i = 0; i < head.length; i++) {
            vector[i] = head[i];
            sum += head[i] * head[i];
        }
        double norm = Math.sqrt(sum);
        for (int i = 0; i < head.length; i++) {
            vector[i] = (float) (vector[i] / norm);
        }
        return vector;
    }
}
