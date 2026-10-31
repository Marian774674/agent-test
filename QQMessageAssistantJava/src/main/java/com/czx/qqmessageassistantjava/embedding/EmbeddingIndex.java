package com.czx.qqmessageassistantjava.embedding;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * 进程内的暴力余弦索引：向量存放在堆外 {@link ByteBuffer}，线性扫描求点积。
 *
 * <p>索引中的向量约定为 L2 归一化后的向量，因此点积即余弦相似度。
 * 维度在构造时固定；{@link #append(long, float[])} 与 {@link #search(float[], int)}
 * 都是 {@code synchronized}，且 {@code search} 在锁内完成整次扫描，
 * 便于索引加载任务与检索请求并发时保持一致。
 *
 * <p>本类不负责持久化：由调用方从 {@code message_embedding} 解码出 {@code float[]} 后写入。
 */
public final class EmbeddingIndex {

    /** 一条命中记录：消息 id 与相似度得分（已归一化向量的点积）。 */
    public record ScoredId(long messageId, float score) {
    }

    /** 初始容量；满时翻倍。 */
    private static final int INITIAL_CAPACITY = 16;

    /** 最小堆比较器：得分低者（或同分时 id 大者）排在堆顶，便于淘汰最差命中。 */
    private static final Comparator<ScoredId> WORST_FIRST =
            Comparator.comparingDouble(ScoredId::score)
                    .thenComparing(Comparator.comparingLong(ScoredId::messageId).reversed());

    /** 最终结果顺序：得分高者在前，同分时 id 小者在前，保证结果稳定。 */
    private static final Comparator<ScoredId> BEST_FIRST = WORST_FIRST.reversed();

    private final int dim;
    private ByteBuffer buffer;
    private long[] ids;
    private int capacity;
    private int size;

    /**
     * @param dim 向量维度，必须为正；与 {@link EmbeddingService#dim()} 保持一致
     * @throws IllegalArgumentException 维度非正时
     */
    public EmbeddingIndex(int dim) {
        if (dim <= 0) {
            throw new IllegalArgumentException("dim must be positive: " + dim);
        }
        this.dim = dim;
        this.capacity = INITIAL_CAPACITY;
        this.ids = new long[capacity];
        this.buffer = allocate(capacity);
    }

    /**
     * 追加一条向量。重复的 messageId 不做去重，由调用方保证。
     *
     * @throws IllegalArgumentException 向量为 null 或长度与构造维度不一致时
     */
    public synchronized void append(long messageId, float[] vector) {
        if (vector == null || vector.length != dim) {
            throw new IllegalArgumentException(
                    "vector dimension mismatch: expected " + dim
                            + ", got " + (vector == null ? "null" : vector.length));
        }
        ensureCapacity(size + 1);

        int offset = size * dim * Float.BYTES;
        for (int d = 0; d < dim; d++) {
            buffer.putFloat(offset + d * Float.BYTES, vector[d]);
        }
        ids[size] = messageId;
        size++;
    }

    /**
     * 返回与 query 最相似的前 {@code topK} 条记录，得分从高到低。
     *
     * <p>{@code topK <= 0}、索引为空或 {@code topK > size()} 均安全：
     * 分别返回空列表、空列表与全部记录。
     *
     * @throws IllegalArgumentException query 为 null 或长度与构造维度不一致时
     */
    public synchronized List<ScoredId> search(float[] query, int topK) {
        if (query == null || query.length != dim) {
            throw new IllegalArgumentException(
                    "query dimension mismatch: expected " + dim
                            + ", got " + (query == null ? "null" : query.length));
        }
        if (topK <= 0 || size == 0) {
            return List.of();
        }

        int limit = Math.min(topK, size);
        PriorityQueue<ScoredId> heap = new PriorityQueue<>(limit, WORST_FIRST);
        ByteBuffer current = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);

        for (int row = 0; row < size; row++) {
            int offset = row * dim * Float.BYTES;
            double dot = 0d;
            for (int d = 0; d < dim; d++) {
                dot += (double) current.getFloat(offset + d * Float.BYTES) * query[d];
            }
            ScoredId candidate = new ScoredId(ids[row], (float) dot);

            if (heap.size() < limit) {
                heap.add(candidate);
            } else if (WORST_FIRST.compare(candidate, heap.peek()) > 0) {
                heap.poll();
                heap.add(candidate);
            }
        }

        List<ScoredId> result = new ArrayList<>(heap);
        result.sort(BEST_FIRST);
        return result;
    }

    /** 当前索引中的记录数。 */
    public synchronized int size() {
        return size;
    }

    /** 当前索引是否包含该消息 id。 */
    public synchronized boolean contains(long messageId) {
        for (int i = 0; i < size; i++) {
            if (ids[i] == messageId) {
                return true;
            }
        }
        return false;
    }

    /** 清空索引内容，但保留堆外缓冲区，后续 {@link #append} 可继续使用。 */
    public synchronized void reset() {
        size = 0;
        buffer.clear();
    }

    /** 扩容到至少 {@code required} 条，并把已有字节搬到新缓冲区。 */
    private void ensureCapacity(int required) {
        if (required <= capacity) {
            return;
        }
        int newCapacity = capacity;
        while (newCapacity < required) {
            newCapacity = Math.max(newCapacity * 2, required);
        }

        ByteBuffer grown = allocate(newCapacity);
        ByteBuffer source = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        source.position(0).limit(size * dim * Float.BYTES);
        grown.put(source);

        this.buffer = grown;
        this.ids = Arrays.copyOf(ids, newCapacity);
        this.capacity = newCapacity;
    }

    private ByteBuffer allocate(int rows) {
        int bytes = Math.multiplyExact(Math.multiplyExact(rows, dim), Float.BYTES);
        return ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }
}
