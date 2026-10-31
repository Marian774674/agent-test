package com.czx.qqmessageassistantjava.service.impl;

import com.czx.qqmessageassistantjava.embedding.EmbeddingIndex;
import com.czx.qqmessageassistantjava.embedding.EmbeddingProperties;
import com.czx.qqmessageassistantjava.embedding.EmbeddingService;
import com.czx.qqmessageassistantjava.entity.MessageEmbedding;
import com.czx.qqmessageassistantjava.mapper.MessageEmbeddingMapper;
import com.czx.qqmessageassistantjava.service.EmbeddingJobService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 索引任务实现。
 *
 * <p>三个会改动索引的方法共用服务实例这一把锁：定时任务与「提问前预跑」可能同时
 * 触发 {@link #indexMissing(int)}，若不加锁，同一批 missing 行会被两次 {@code selectMissing}
 * 读到、两次 {@code upsert}、两次 {@code append}，索引里就会出现重复 id。
 * 这里不用 {@code contains()} 去重——那会让批量索引退化成 O(n²)。
 */
@Service
public class EmbeddingJobServiceImpl implements EmbeddingJobService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingJobServiceImpl.class);

    /** 定时任务每轮最多处理的条数；别让一次全量扫描卡住调度线程。 */
    private static final int SCHEDULED_BATCH = 64;

    /** 重建时每批处理条数。 */
    private static final int REBUILD_BATCH = 500;

    private final EmbeddingService embeddingService;
    private final MessageEmbeddingMapper messageEmbeddingMapper;
    private final EmbeddingIndex index;
    private final int indexDim;

    /**
     * 加载成功标记。启动时加载失败/模型未就绪都保持 false，
     * 让 {@link #status()} 把语义通道报告为关闭，而不是把异常抛到 ApplicationReadyEvent 里。
     */
    private volatile boolean indexLoaded;

    public EmbeddingJobServiceImpl(EmbeddingService embeddingService,
                                   MessageEmbeddingMapper messageEmbeddingMapper,
                                   EmbeddingIndex index,
                                   EmbeddingProperties properties) {
        this.embeddingService = embeddingService;
        this.messageEmbeddingMapper = messageEmbeddingMapper;
        this.index = index;
        this.indexDim = properties.getDim();
    }

    @Override
    public synchronized int indexMissing(int limit) {
        if (!embeddingService.available()) {
            return 0;
        }
        List<MessageEmbeddingMapper.MissingMessage> missing =
                messageEmbeddingMapper.selectMissing(embeddingService.modelId(), limit);
        if (missing.isEmpty()) {
            return 0;
        }

        List<String> contents = missing.stream()
                .map(MessageEmbeddingMapper.MissingMessage::content)
                .toList();
        List<float[]> vectors = embeddingService.embedBatch(contents);
        if (vectors.size() != missing.size()) {
            throw new IllegalStateException("embedBatch 返回条数与输入不一致："
                    + vectors.size() + " != " + missing.size());
        }

        String model = embeddingService.modelId();
        int indexed = 0;
        for (int i = 0; i < missing.size(); i++) {
            long messageId = missing.get(i).id();
            float[] vector = vectors.get(i);

            MessageEmbedding row = new MessageEmbedding();
            row.setMessageLogId(messageId);
            row.setModel(model);
            row.setDim(vector.length);
            row.setVector(encode(vector));
            messageEmbeddingMapper.upsert(row);

            index.append(messageId, vector);
            indexed++;
        }
        return indexed;
    }

    @Override
    public synchronized int rebuild() {
        if (!embeddingService.available()) {
            // 模型没就绪时删了也补不回来，别把已有向量清空
            log.warn("向量重建跳过：向量服务不可用");
            return 0;
        }
        int removed = messageEmbeddingMapper.deleteByModel(embeddingService.modelId());
        int total = 0;
        while (true) {
            int indexed = indexMissing(REBUILD_BATCH);
            total += indexed;
            if (indexed == 0) {
                break;
            }
        }
        loadIndex();
        log.info("向量重建完成：删除 {} 行，重新写入 {} 行，索引 {} 条", removed, total, index.size());
        return total;
    }

    @Override
    public EmbeddingStatus status() {
        boolean semantic = embeddingService.available() && indexLoaded;
        return new EmbeddingStatus(
                semantic,
                index.size(),
                messageEmbeddingMapper.countMissing(embeddingService.modelId()));
    }

    @Override
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void loadIndex() {
        indexLoaded = false;
        // 先清空：上一次加载的旧向量不能在模型失效后继续被当成有效数据
        index.reset();

        if (!embeddingService.available()) {
            log.warn("语义索引未加载：向量服务不可用，语义通道关闭");
            return;
        }

        String model = embeddingService.modelId();
        AtomicInteger loaded = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        try {
            messageEmbeddingMapper.selectAll(model, context -> {
                MessageEmbedding row = context.getResultObject();
                byte[] bytes = row.getVector();
                Integer dim = row.getDim();
                if (dim == null || dim != indexDim
                        || bytes == null || bytes.length != indexDim * Float.BYTES) {
                    int count = skipped.incrementAndGet();
                    if (count == 1) {
                        log.warn("语义索引跳过维度不符的行：messageLogId={}, dim={}, bytes={}, 期望 dim={}",
                                row.getMessageLogId(), dim, bytes == null ? 0 : bytes.length, indexDim);
                    }
                    return;
                }
                // 流式 ResultHandler 里不能再发查询——同一连接上有未消费的结果集
                index.append(row.getMessageLogId(), decode(bytes));
                loaded.incrementAndGet();
            });
            indexLoaded = true;
            log.info("语义索引已加载：model={}, rows={}, skipped={}", model, loaded.get(), skipped.get());
        } catch (Exception e) {
            // 启动加载失败不能拖垮应用：清空索引、语义通道关闭，其余检索照常
            index.reset();
            indexLoaded = false;
            log.error("语义索引加载失败，语义通道关闭：model={}", model, e);
        }
    }

    /**
     * 增量索引的定时兜底。fixedDelay 从上一轮结束开始计时，避免模型慢时任务堆积；
     * 首次执行默认立即开始，测试环境把 initialDelay 调大以隔离后台写入。
     */
    @Scheduled(fixedDelayString = "${app.embedding.index-interval-ms:60000}",
            initialDelayString = "${app.embedding.index-initial-delay-ms:0}")
    public void scheduledIndexMissing() {
        try {
            int indexed = indexMissing(SCHEDULED_BATCH);
            if (indexed > 0) {
                log.info("增量索引：写入 {} 条向量", indexed);
            }
        } catch (Exception e) {
            // 调度器会吞掉异常且不再触发后续，这里必须自己兜住
            log.error("增量索引任务失败", e);
        }
    }

    private static byte[] encode(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : vector) {
            buffer.putFloat(value);
        }
        return buffer.array();
    }

    private static float[] decode(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] vector = new float[bytes.length / Float.BYTES];
        buffer.asFloatBuffer().get(vector);
        return vector;
    }
}
