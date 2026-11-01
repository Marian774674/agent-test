package com.czx.qqmessageassistantjava.retrieval;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 多通道 RRF（Reciprocal Rank Fusion）融合。
 *
 * <p>纯内存逻辑：不依赖 Spring、数据库或任何外部状态。融合流程依次为
 * 按通道内名次计分 → 按 key 汇总并按分数排序 → 时间软分段 → 字符预算与条目上限截断 →
 * 语义/词法通道前 10 保底追加。
 *
 * <p>输入只读：不会修改调用方传入的列表，也不会依赖 {@code HashMap} 的迭代顺序做决策。
 */
public final class RrfFusion {

    /** RRF 平滑常数，见 spec §7.4。 */
    private static final int RRF_K = 60;

    /** 每个受保底通道最多要求出现的候选数。 */
    private static final int GUARANTEED_CHANNEL_TOP = 10;

    /** 每个候选的固定字符开销（分隔符/元数据近似值）。 */
    private static final int ITEM_CHAR_OVERHEAD = 16;

    private RrfFusion() {
    }

    /**
     * 融合七路召回结果。
     *
     * @param result    各通道召回结果（允许为 null 或缺失某些通道）
     * @param charBudget 字符预算，按 {@code label.length() + content.length() + 16} 累加
     * @param maxItems  输出条目硬上限
     * @param from      时间下界（含），null 表示不限
     * @param to        时间上界（含），null 表示不限
     * @return 按 RRF 分数与时间软分段排好序的候选列表
     */
    public static List<Candidate> fuse(RetrievalResult result, int charBudget, int maxItems,
                                       LocalDateTime from, LocalDateTime to) {
        if (result == null || result.hits() == null || maxItems <= 0) {
            return List.of();
        }

        Map<String, Double> scores = new HashMap<>();
        Map<String, Candidate> byKey = new HashMap<>();
        collectScores(result, scores, byKey);

        List<String> ranked = rankKeys(scores);
        List<String> tiered = applyTimeTiers(ranked, byKey, from, to);

        List<Candidate> selected = selectWithinBudget(tiered, byKey, charBudget, maxItems);
        Set<String> selectedKeys = new HashSet<>();
        for (Candidate candidate : selected) {
            selectedKeys.add(candidate.key());
        }
        appendGuaranteed(result, scores, byKey, selected, selectedKeys, maxItems);
        return selected;
    }

    /**
     * 通道内下标即名次（1-based 计分）：第 {@code i} 位贡献 {@code 1.0 / (60 + i + 1)}。
     * 同一通道内同一 key 只取首次（最好）出现的名次，不重复累加；
     * 同一 key 出现在不同通道时，各通道的贡献仍然相加。代表条目取首次出现者。
     */
    private static void collectScores(RetrievalResult result, Map<String, Double> scores,
                                      Map<String, Candidate> byKey) {
        for (Channel channel : Channel.values()) {
            List<Candidate> list = result.hits().get(channel);
            if (list == null) {
                continue;
            }
            Set<String> seenInChannel = new HashSet<>();
            for (int i = 0; i < list.size(); i++) {
                Candidate candidate = list.get(i);
                if (candidate == null || !seenInChannel.add(candidate.key())) {
                    continue;
                }
                scores.merge(candidate.key(), 1.0 / (RRF_K + i + 1), Double::sum);
                byKey.putIfAbsent(candidate.key(), candidate);
            }
        }
    }

    /** 分数降序、key 升序的确定性全序。 */
    private static List<String> rankKeys(Map<String, Double> scores) {
        List<String> ranked = new ArrayList<>(scores.keySet());
        ranked.sort(Comparator.<String>comparingDouble(key -> scores.get(key)).reversed()
                .thenComparing(Comparator.naturalOrder()));
        return ranked;
    }

    /**
     * 时间软分段：A 段为 at 非空且落在 [from, to]（null 边界表示不限），B 段为其余（含 at==null）。
     * 段内保持 RRF 相对顺序；两端都为 null 时跳过分段。
     */
    private static List<String> applyTimeTiers(List<String> ranked, Map<String, Candidate> byKey,
                                               LocalDateTime from, LocalDateTime to) {
        if (from == null && to == null) {
            return ranked;
        }
        List<String> inRange = new ArrayList<>(ranked.size());
        List<String> rest = new ArrayList<>();
        for (String key : ranked) {
            if (withinRange(byKey.get(key).at(), from, to)) {
                inRange.add(key);
            } else {
                rest.add(key);
            }
        }
        inRange.addAll(rest);
        return inRange;
    }

    /**
     * 在分层后的 RRF 顺序上按字符预算与条目上限取条目。预算按
     * {@code label.length() + content.length() + 16}（null 记 0）累加。
     * 放不下的条目跳过但继续向后扫描，只有达到 maxItems 才停止，
     * 避免一个超长条目浪费掉后面本可容纳的条目。
     */
    private static List<Candidate> selectWithinBudget(List<String> tiered, Map<String, Candidate> byKey,
                                                      int charBudget, int maxItems) {
        List<Candidate> selected = new ArrayList<>();
        int usedChars = 0;
        for (String key : tiered) {
            if (selected.size() >= maxItems) {
                break;
            }
            Candidate candidate = byKey.get(key);
            int cost = charCost(candidate);
            if (usedChars + cost <= charBudget) {
                usedChars += cost;
                selected.add(candidate);
            }
        }
        return selected;
    }

    /**
     * 保底：语义与词法通道各自的前 10 名（按通道内部名次）必须出现在输出中。
     * 预算截断后缺失的条目按 RRF 顺序追加，绕过字符预算但不超过 maxItems；重复 key 只加一次。
     */
    private static void appendGuaranteed(RetrievalResult result, Map<String, Double> scores,
                                         Map<String, Candidate> byKey, List<Candidate> selected,
                                         Set<String> selectedKeys, int maxItems) {
        List<Candidate> guaranteed = new ArrayList<>();
        Set<String> guaranteedKeys = new HashSet<>();
        for (Channel channel : List.of(Channel.MSG_SEMANTIC, Channel.MSG_LEXICAL)) {
            List<Candidate> list = result.hits().get(channel);
            if (list == null) {
                continue;
            }
            int limit = Math.min(GUARANTEED_CHANNEL_TOP, list.size());
            for (int i = 0; i < limit; i++) {
                Candidate candidate = list.get(i);
                if (candidate != null && guaranteedKeys.add(candidate.key())) {
                    guaranteed.add(candidate);
                }
            }
        }

        guaranteed.sort(Comparator.<Candidate>comparingDouble(c -> scores.getOrDefault(c.key(), 0.0))
                .reversed()
                .thenComparing(Candidate::key));

        for (Candidate candidate : guaranteed) {
            if (selected.size() >= maxItems) {
                break;
            }
            if (selectedKeys.add(candidate.key())) {
                selected.add(candidate);
            }
        }
    }

    private static boolean withinRange(LocalDateTime at, LocalDateTime from, LocalDateTime to) {
        if (at == null) {
            return false;
        }
        if (from != null && at.isBefore(from)) {
            return false;
        }
        return to == null || !at.isAfter(to);
    }

    private static int charCost(Candidate candidate) {
        return length(candidate.label()) + length(candidate.content()) + ITEM_CHAR_OVERHEAD;
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }
}
