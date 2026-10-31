package com.czx.qqmessageassistantjava.retrieval;

import com.czx.qqmessageassistantjava.embedding.EmbeddingIndex;
import com.czx.qqmessageassistantjava.embedding.EmbeddingService;
import com.czx.qqmessageassistantjava.entity.Memo;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

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

        void setAvailable(boolean available) {
            this.available = available;
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

    @Autowired
    private MemoMapper memoMapper;

    @Autowired
    private FileTaskMapper fileTaskMapper;

    @BeforeEach
    void resetState() {
        index.reset();
        fake.setAvailable(false);
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

        var plan = new QueryPlan(Set.of(), List.of(), List.of(),
                List.of("q1", "q2", "q3"), null, null);

        var result = service.retrieve(plan, Set.of());

        List<String> keys = result.hits().get(Channel.MSG_SEMANTIC).stream()
                .map(Candidate::key).toList();
        assertThat(keys).isNotEmpty();
        assertThat(keys.get(0)).isEqualTo("msg:" + target);
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
                brokenNoteMapper, messageLogMapper, memoMapper, fileTaskMapper, fake, index);

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

    private void insertMessage(long id, Long chatId, String chatName, long senderId, String senderName,
                               String content, LocalDateTime createdAt) {
        MessageLog row = new MessageLog();
        row.setId(id);
        row.setSenderId(senderId);
        row.setSenderName(senderName);
        row.setChatId(chatId);
        row.setChatName(chatName);
        row.setConversationId("conv-" + id);
        row.setMessageId("msg-" + id);
        row.setDedupeKey("retrieval-seed-" + id);
        row.setDirection("IN");
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
