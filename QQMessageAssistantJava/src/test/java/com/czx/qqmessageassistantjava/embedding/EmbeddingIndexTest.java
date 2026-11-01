package com.czx.qqmessageassistantjava.embedding;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link EmbeddingIndex} 的纯单元测试：不启动 Spring，也不加载模型。
 *
 * <p>索引中的向量约定为 L2 归一化后的向量，因此点积即余弦相似度。
 */
class EmbeddingIndexTest {

    @Test
    void search_returns_nearest_first() {
        var idx = new EmbeddingIndex(3);
        idx.append(1, vec(1, 0, 0));
        idx.append(2, vec(0, 1, 0));
        idx.append(3, vec(0.9f, 0.1f, 0));

        assertThat(idx.search(vec(1, 0, 0), 2))
                .extracting(EmbeddingIndex.ScoredId::messageId)
                .containsExactly(1L, 3L);
    }

    @Test
    void reset_then_append_grows_without_rebuild() {
        var idx = new EmbeddingIndex(3);
        idx.append(1, vec(1, 0, 0));
        idx.append(2, vec(0, 1, 0));

        idx.reset();

        assertThat(idx.size()).isZero();
        assertThat(idx.search(vec(1, 0, 0), 5)).isEmpty();

        idx.append(7, vec(0, 0, 1));

        assertThat(idx.size()).isEqualTo(1);
        assertThat(idx.contains(7)).isTrue();
        assertThat(idx.contains(1)).isFalse();
        assertThat(idx.search(vec(1, 0, 0), 5))
                .extracting(EmbeddingIndex.ScoredId::messageId)
                .containsExactly(7L);
    }

    @Test
    void dimension_mismatch_is_rejected() {
        var idx = new EmbeddingIndex(3);
        idx.append(1, vec(1, 0, 0));

        assertThatThrownBy(() -> idx.append(9, vec(1, 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> idx.search(vec(1, 0), 1))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(idx.size()).isEqualTo(1);
    }

    @Test
    void constructor_rejects_non_positive_dimension() {
        assertThatThrownBy(() -> new EmbeddingIndex(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EmbeddingIndex(-3))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void non_positive_topK_returns_empty_even_when_index_has_entries() {
        var idx = new EmbeddingIndex(2);
        idx.append(1, vec(1, 0));

        assertThat(idx.search(vec(1, 0), 0)).isEmpty();
        assertThat(idx.search(vec(1, 0), -1)).isEmpty();
    }

    @Test
    void search_on_empty_index_returns_empty() {
        var idx = new EmbeddingIndex(2);

        assertThat(idx.search(vec(1, 0), 5)).isEmpty();
    }

    @Test
    void topK_larger_than_size_returns_all_best_first_including_negative_similarity() {
        var idx = new EmbeddingIndex(2);
        idx.append(1, vec(1, 0));
        idx.append(2, vec(0.6f, 0.8f));
        idx.append(3, vec(-1, 0));

        List<EmbeddingIndex.ScoredId> hits = idx.search(vec(1, 0), 10);

        assertThat(hits).extracting(EmbeddingIndex.ScoredId::messageId)
                .containsExactly(1L, 2L, 3L);
        assertThat(hits.get(0).score()).isEqualTo(1.0f);
        assertThat(hits.get(1).score()).isEqualTo(0.6f);
        assertThat(hits.get(2).score()).isEqualTo(-1.0f);
    }

    @Test
    void capacity_grows_without_losing_entries() {
        var idx = new EmbeddingIndex(4);
        for (int i = 0; i < 100; i++) {
            float[] vector = new float[4];
            vector[i % 4] = 1f;
            idx.append(i, vector);
        }

        assertThat(idx.size()).isEqualTo(100);
        assertThat(idx.contains(0)).isTrue();
        assertThat(idx.contains(99)).isTrue();
        assertThat(idx.contains(100)).isFalse();

        // 同分时按 messageId 升序，保证结果稳定
        assertThat(idx.search(vec(1, 0, 0, 0), 3))
                .extracting(EmbeddingIndex.ScoredId::messageId)
                .containsExactly(0L, 4L, 8L);
    }

    private static float[] vec(float... values) {
        return values;
    }
}
