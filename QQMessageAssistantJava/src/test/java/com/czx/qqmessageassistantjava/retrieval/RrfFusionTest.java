package com.czx.qqmessageassistantjava.retrieval;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RrfFusion} 的纯单元测试：不启动 Spring，不依赖数据库。
 *
 * <p>覆盖 RRF 跨通道加权、时间软分段、字符预算、条目上限与语义/词法前 10 保底。
 */
class RrfFusionTest {

    // ---- brief 的三个测试 ----

    @Test
    void rrf_prefers_item_hit_by_multiple_channels() {
        var a = cand("msg", 1);
        var b = cand("msg", 2);
        var r = new RetrievalResult(Map.of(
                Channel.MSG_LEXICAL, List.of(a),
                Channel.MSG_SEMANTIC, List.of(a, b)));

        assertThat(RrfFusion.fuse(r, 30_000, 150, null, null))
                .extracting(Candidate::key).containsExactly("msg:1", "msg:2");
    }

    @Test
    void time_range_puts_in_range_first_without_dropping_others() {
        var old = candAt("msg", 1, LocalDateTime.of(2024, 1, 1, 0, 0));
        var recent = candAt("msg", 2, LocalDateTime.of(2026, 10, 1, 0, 0));
        var r = new RetrievalResult(Map.of(Channel.MSG_LEXICAL, List.of(old, recent)));

        assertThat(RrfFusion.fuse(r, 30_000, 150, LocalDateTime.of(2026, 1, 1, 0, 0), null))
                .extracting(Candidate::key).containsExactly("msg:2", "msg:1");
    }

    @Test
    void char_budget_truncates_but_keeps_semantic_top10() {
        var semantic = many("sem", 1, 30);
        var lexical = many("lex", 1, 30);
        var r = new RetrievalResult(Map.of(
                Channel.MSG_SEMANTIC, semantic,
                Channel.MSG_LEXICAL, lexical));

        var out = RrfFusion.fuse(r, 200, 150, null, null);
        Set<String> keys = keys(out);

        assertThat(keys).containsAll(rangeKeys("sem", 1, 10));
        assertThat(keys).containsAll(rangeKeys("lex", 1, 10));
        assertThat(out).hasSizeLessThanOrEqualTo(150);
        // 小预算确实发生了截断（若无截断，60 条全部会在）
        assertThat(out).hasSizeLessThan(60);
    }

    // ---- 追加测试 ----

    @Test
    void rrf_sums_across_channels_and_dedupes_by_key() {
        var shared = cand("same", 1);
        var single = cand("other", 1);
        var r = new RetrievalResult(Map.of(
                Channel.NOTE, List.of(shared),
                Channel.MEMO, List.of(shared),
                Channel.FILE, List.of(shared),
                Channel.MSG_PERSON, List.of(single)));

        var out = RrfFusion.fuse(r, 30_000, 150, null, null);

        assertThat(out).extracting(Candidate::key).containsExactly("same:1", "other:1");
        assertThat(keys(out)).hasSize(2);
    }

    @Test
    void guaranteed_semantic_top10_survives_tiny_budget() {
        var semantic = many("sem", 1, 12);
        var lexical = many("lex", 1, 40);
        var r = new RetrievalResult(Map.of(
                Channel.MSG_SEMANTIC, semantic,
                Channel.MSG_LEXICAL, lexical));

        var out = RrfFusion.fuse(r, 100, 150, null, null);
        Set<String> keys = keys(out);

        assertThat(keys).containsAll(rangeKeys("sem", 1, 10));
        assertThat(out).hasSizeLessThanOrEqualTo(150);
        assertThat(keys).doesNotHaveDuplicates();
    }

    @Test
    void time_range_with_null_at_keeps_candidates_after_in_range() {
        var noAt = cand("msg", 1); // 通道内名次 1，但 at 为 null
        var inRange = candAt("msg", 2, LocalDateTime.of(2026, 10, 1, 0, 0)); // 名次 2
        var r = new RetrievalResult(Map.of(Channel.NOTE, List.of(noAt, inRange)));

        assertThat(RrfFusion.fuse(r, 30_000, 150, LocalDateTime.of(2026, 1, 1, 0, 0), null))
                .extracting(Candidate::key).containsExactly("msg:2", "msg:1");
    }

    @Test
    void max_items_caps_output() {
        var r = new RetrievalResult(Map.of(Channel.NOTE, many("note", 1, 20)));

        assertThat(RrfFusion.fuse(r, 30_000, 5, null, null)).hasSize(5);
    }

    @Test
    void does_not_mutate_caller_lists_and_skips_null_entries() {
        var a = cand("note", 1);
        var b = cand("note", 2);
        var input = new ArrayList<Candidate>();
        input.add(null);
        input.add(b); // 通道内下标 1
        input.add(a); // 通道内下标 2
        var snapshot = new ArrayList<>(input);
        var r = new RetrievalResult(Map.of(Channel.NOTE, input));

        assertThat(RrfFusion.fuse(r, 30_000, 150, null, null))
                .extracting(Candidate::key).containsExactly("note:2", "note:1");
        assertThat(input).containsExactlyElementsOf(snapshot);
    }

    // ---- helpers ----

    private static Candidate cand(String type, long id) {
        return new Candidate(type, id, "label" + id, "content" + id, null, "test", "quote" + id);
    }

    private static Candidate candAt(String type, long id, LocalDateTime at) {
        return new Candidate(type, id, "label" + id, "content" + id, at, "test", "quote" + id);
    }

    private static List<Candidate> many(String type, int fromInclusive, int toInclusive) {
        return IntStream.rangeClosed(fromInclusive, toInclusive)
                .mapToObj(i -> cand(type, i))
                .toList();
    }

    private static Set<String> keys(List<Candidate> candidates) {
        return candidates.stream().map(Candidate::key).collect(Collectors.toSet());
    }

    private static List<String> rangeKeys(String type, int fromInclusive, int toInclusive) {
        return IntStream.rangeClosed(fromInclusive, toInclusive)
                .mapToObj(i -> type + ":" + i)
                .toList();
    }
}
