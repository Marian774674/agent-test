package com.czx.qqmessageassistantjava.service;

import com.czx.qqmessageassistantjava.embedding.EmbeddingIndex;
import com.czx.qqmessageassistantjava.embedding.EmbeddingService;
import com.czx.qqmessageassistantjava.entity.MessageEmbedding;
import com.czx.qqmessageassistantjava.entity.MessageLog;
import com.czx.qqmessageassistantjava.mapper.MessageEmbeddingMapper;
import com.czx.qqmessageassistantjava.mapper.MessageLogMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 索引任务：增量写入、状态统计、重建、启动加载。
 *
 * <p>用 {@link FakeEmbeddingService}（{@code @Primary}）替换真实的 ONNX 服务：
 * 向量由文本确定性推导，512 维、L2 归一化，模型 id 固定为 {@code fake}。
 * 测试事务内的写入对后台调度线程不可见；测试配置把调度首次执行推迟到 1 小时后，
 * 保证不会在断言过程中往同一个索引里追加数据。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class EmbeddingJobServiceTest {

    private static final String MODEL = "fake";
    private static final int DIM = 512;

    @TestConfiguration
    static class FakeEmbeddingConfig {

        @Bean
        @Primary
        EmbeddingService fakeEmbeddingService() {
            return new FakeEmbeddingService();
        }
    }

    static final class FakeEmbeddingService implements EmbeddingService {

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public String modelId() {
            return MODEL;
        }

        @Override
        public int dim() {
            return DIM;
        }

        @Override
        public float[] embed(String text) {
            return unitVector(text);
        }

        @Override
        public List<float[]> embedBatch(List<String> texts) {
            return texts.stream().map(this::embed).toList();
        }

        /** 确定性、可区分、L2 归一化；同一文本永远得到同一向量。 */
        static float[] unitVector(String text) {
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
    private EmbeddingJobService service;

    @Autowired
    private MessageEmbeddingMapper embeddingMapper;

    @Autowired
    private MessageLogMapper messageLogMapper;

    @Autowired
    private EmbeddingIndex index;

    @Test
    void indexMissing_writes_vectors_and_is_idempotent() {
        long id = 9_910_001L;
        insertIn(id, "小明生日11月11");
        try {
            int n1 = service.indexMissing(10);
            int n2 = service.indexMissing(10);

            assertThat(n1).isEqualTo(1);
            assertThat(n2).isEqualTo(0);
            assertThat(embeddingMapper.countByModel(MODEL)).isEqualTo(1);
            assertThat(index.contains(id)).isTrue();
        } finally {
            cleanup(id);
        }
    }

    @Test
    void status_reports_lag() {
        long first = 9_910_011L;
        long second = 9_910_012L;
        insertIn(first, "第一条待索引");
        insertIn(second, "第二条待索引");
        try {
            // 先把内存索引对齐到本事务可见的持久化状态，避免其它用例残留的条目干扰计数
            service.loadIndex();

            assertThat(service.indexMissing(1)).isEqualTo(1);

            EmbeddingJobService.EmbeddingStatus status = service.status();
            assertThat(status.semantic()).isTrue();
            assertThat(status.indexed()).isEqualTo(1);
            assertThat(status.lag()).isEqualTo(1);
        } finally {
            cleanup(first, second);
        }
    }

    @Test
    void rebuild_clears_and_reindexes() {
        long first = 9_910_021L;
        long second = 9_910_022L;
        insertIn(first, "重建第一条");
        insertIn(second, "重建第二条");
        try {
            assertThat(service.indexMissing(10)).isEqualTo(2);
            assertThat(embeddingMapper.countByModel(MODEL)).isEqualTo(2);

            assertThat(service.rebuild()).isEqualTo(2);

            assertThat(embeddingMapper.countByModel(MODEL)).isEqualTo(2);
            assertThat(index.size()).isEqualTo(2);
            assertThat(index.contains(first)).isTrue();
            assertThat(index.contains(second)).isTrue();
        } finally {
            cleanup(first, second);
        }
    }

    @Test
    void loadIndex_streams_persisted_vectors_into_index() {
        long first = 9_910_031L;
        long second = 9_910_032L;
        float[] firstVector = FakeEmbeddingService.unitVector("向量一");
        float[] secondVector = FakeEmbeddingService.unitVector("向量二");
        try {
            embeddingMapper.upsert(embedding(first, firstVector));
            embeddingMapper.upsert(embedding(second, secondVector));

            service.loadIndex();

            assertThat(index.size()).isEqualTo(2);
            assertThat(index.contains(first)).isTrue();
            assertThat(index.contains(second)).isTrue();

            List<EmbeddingIndex.ScoredId> hits = index.search(firstVector, 2);
            assertThat(hits).isNotEmpty();
            assertThat(hits.get(0).messageId()).isEqualTo(first);
        } finally {
            cleanup(first, second);
        }
    }

    @Test
    void loadIndex_skips_row_with_wrong_dimension() {
        long good = 9_910_041L;
        long wrong = 9_910_042L;
        try {
            embeddingMapper.upsert(embedding(good, FakeEmbeddingService.unitVector("维度正确")));
            embeddingMapper.upsert(embedding(wrong, new float[]{1f, 0f, 0f, 0f}));

            service.loadIndex();

            assertThat(index.size()).isEqualTo(1);
            assertThat(index.contains(good)).isTrue();
            assertThat(index.contains(wrong)).isFalse();
        } finally {
            cleanup(good, wrong);
        }
    }

    private MessageEmbedding embedding(long messageLogId, float[] vector) {
        MessageEmbedding row = new MessageEmbedding();
        row.setMessageLogId(messageLogId);
        row.setModel(MODEL);
        row.setDim(vector.length);
        row.setVector(toBytes(vector));
        return row;
    }

    private static byte[] toBytes(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : vector) {
            buffer.putFloat(value);
        }
        return buffer.array();
    }

    /** 所有 NOT NULL 列都给确定值；dedupe_key 有唯一约束，按 id 造。 */
    private void insertIn(long id, String content) {
        MessageLog row = new MessageLog();
        row.setId(id);
        row.setSenderId(100L);
        row.setSenderName("测试");
        row.setChatId(9_910_000L);
        row.setChatName("测试群");
        row.setConversationId("conv-" + id);
        row.setMessageId("msg-" + id);
        row.setDedupeKey("seed-" + id);
        row.setDirection("IN");
        row.setMsgType("text");
        row.setContent(content);
        row.setCreatedAt(LocalDateTime.of(2026, 10, 31, 12, 0));
        messageLogMapper.insert(row);
    }

    /**
     * 事务回滚已经能清掉本用例的数据；这里再显式删一次，兜住后台调度线程
     * 踩到同一条消息时留下的已提交向量行。
     */
    private void cleanup(long... ids) {
        embeddingMapper.deleteByModel(MODEL);
        for (long id : ids) {
            messageLogMapper.deleteById(id);
        }
    }
}
