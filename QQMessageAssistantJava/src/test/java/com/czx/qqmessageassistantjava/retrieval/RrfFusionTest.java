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
        // 若预算截断失效会保留全部 52 条；精确断言同时证明截断真实发生
        assertThat(out).hasSize(20);
        assertThat(out).extracting(Candidate::key).doesNotHaveDuplicates();
        assertThat(out).extracting(Candidate::key).containsExactly(
                "lex:1", "sem:1", "lex:2", "sem:2", "lex:3", "sem:3", "lex:4", "sem:4",
                "lex:5", "sem:5", "lex:6", "sem:6", "lex:7", "sem:7", "lex:8", "sem:8",
                "lex:9", "sem:9", "lex:10", "sem:10");
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
    void does_not_mutate_inputs_and_uses_physical_rank_despite_null() {
        var noteTwo = cand("note", 2);
        var zzz = cand("zzz", 1);
        var input = new ArrayList<Candidate>();
        input.add(null);           // 占物理下标 0
        input.add(noteTwo);        // 物理下标 1 => 1/62；若紧凑编号会变成 1/61
        var snapshot = new ArrayList<>(input);
        var r = new RetrievalResult(Map.of(
                Channel.NOTE, input,
                Channel.MEMO, List.of(zzz)));

        // 物理名次：zzz(1/61) 高于 note:2(1/62)。
        // 若跳过 null 后重新紧凑编号，note:2 会与 zzz 同为 1/61，并按 key 升序排在前面。
        assertThat(RrfFusion.fuse(r, 30_000, 150, null, null))
                .extracting(Candidate::key).containsExactly("zzz:1", "note:2");
        assertThat(input).containsExactlyElementsOf(snapshot);
    }

    @Test
    void intra_channel_duplicate_counts_only_best_position() {
        // NOTE 通道中 msg:1 出现在物理下标 4（名次 5）与 9（名次 10）
        var list = new ArrayList<Candidate>();
        list.add(cand("fill", 0));
        list.add(cand("fill", 1));
        list.add(cand("fill", 2));
        list.add(cand("fill", 3));
        list.add(cand("msg", 1)); // 名次 5 => 1/65
        list.add(cand("fill", 4));
        list.add(cand("fill", 5));
        list.add(cand("fill", 6));
        list.add(cand("fill", 7));
        list.add(cand("msg", 1)); // 名次 10 => 若累加则再计 1/70
        var r = new RetrievalResult(Map.of(
                Channel.NOTE, list,
                Channel.MEMO, List.of(cand("msg", 2)))); // 名次 1 => 1/61

        List<String> keys = RrfFusion.fuse(r, 30_000, 150, null, null)
                .stream().map(Candidate::key).toList();

        assertThat(keys.indexOf("msg:2")).isLessThan(keys.indexOf("msg:1"));
        assertThat(keys.stream().filter("msg:1"::equals).count()).isEqualTo(1);
    }

    @Test
    void oversized_item_does_not_stop_smaller_fitting_items() {
        var huge = new Candidate("note", 1, "x".repeat(500), "y".repeat(500), null, "test", "q");
        var list = new ArrayList<Candidate>();
        list.add(huge);              // 开销 1016，远超预算
        list.add(cand("note", 2));   // 开销 30
        list.add(cand("note", 3));
        list.add(cand("note", 4));
        list.add(cand("note", 5));
        var r = new RetrievalResult(Map.of(Channel.NOTE, list));

        assertThat(RrfFusion.fuse(r, 100, 150, null, null))
                .extracting(Candidate::key).containsExactly("note:2", "note:3", "note:4");
    }

    @Test
    void to_only_bound_is_inclusive() {
        var after = candAt("note", 1, LocalDateTime.of(2026, 1, 2, 0, 0));       // 名次 1
        var boundary = candAt("note", 2, LocalDateTime.of(2026, 1, 1, 0, 0));    // 名次 2
        var r = new RetrievalResult(Map.of(Channel.NOTE, List.of(after, boundary)));

        assertThat(RrfFusion.fuse(r, 30_000, 150, null, LocalDateTime.of(2026, 1, 1, 0, 0)))
                .extracting(Candidate::key).containsExactly("note:2", "note:1");
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
