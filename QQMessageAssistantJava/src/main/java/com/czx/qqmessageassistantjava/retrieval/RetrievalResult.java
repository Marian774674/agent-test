package com.czx.qqmessageassistantjava.retrieval;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 七路通道的召回结果。每个通道内的 {@link Candidate} 列表顺序即该通道的内部名次（下标 0 为第 1 名）。
 */
public record RetrievalResult(Map<Channel, List<Candidate>> hits) {

    /**
     * 跨轮合并（spec §7.4 / §7.6）：按通道求并集，同一 {@code type:id} 跨轮只保留**最好**
     * （最小）的名次，因此某个候选在后续轮次排得更靠前时会整体前移，而不是停留在早先的
     * 较差名次。名次相同时保持本轮（{@code this}）的先后顺序，本轮没有的条目按对方顺序
     * 排在后面；代表条目优先取本轮的那份。
     *
     * <p>某一通道在两边都不存在时，合并结果里也保持缺失（表示该路没跑/失败），不会补
     * 一个 {@code null} 进去。
     *
     * @param other 另一轮的结果；{@code null} 视为空结果
     * @return 合并后的新结果，不修改入参
     */
    public RetrievalResult merge(RetrievalResult other) {
        if (other == null) {
            return this;
        }
        Map<Channel, List<Candidate>> left = hits == null ? Map.of() : hits;
        Map<Channel, List<Candidate>> right = other.hits() == null ? Map.of() : other.hits();
        Map<Channel, List<Candidate>> merged = new LinkedHashMap<>();
        for (Channel channel : Channel.values()) {
            List<Candidate> a = left.get(channel);
            List<Candidate> b = right.get(channel);
            if (a == null && b == null) {
                continue;
            }
            if (a == null) {
                merged.put(channel, b);
            } else if (b == null) {
                merged.put(channel, a);
            } else {
                merged.put(channel, mergeChannel(a, b));
            }
        }
        return new RetrievalResult(Collections.unmodifiableMap(merged));
    }

    /**
     * 单通道按「最好名次」求有序并集：先给本轮的首次出现记名次与先后，再用对方的条目
     * 补缺并将名次下调到两者较小值（代表条目仍是本轮的）。排序键依次是最好名次、
     * 来源（本轮优先）、源内顺序。
     */
    private static List<Candidate> mergeChannel(List<Candidate> a, List<Candidate> b) {
        Map<String, Integer> bestRank = new HashMap<>();
        Map<String, Integer> source = new HashMap<>();
        Map<String, Integer> position = new HashMap<>();
        Map<String, Candidate> representative = new HashMap<>();
        index(a, 0, bestRank, source, position, representative);
        index(b, 1, bestRank, source, position, representative);

        List<String> keys = new ArrayList<>(bestRank.keySet());
        keys.sort(Comparator.<String>comparingInt(bestRank::get)
                .thenComparingInt(key -> source.getOrDefault(key, 0))
                .thenComparingInt(key -> position.getOrDefault(key, 0)));
        List<Candidate> out = new ArrayList<>(keys.size());
        for (String key : keys) {
            out.add(representative.get(key));
        }
        return List.copyOf(out);
    }

    private static void index(List<Candidate> list, int source,
                              Map<String, Integer> bestRank,
                              Map<String, Integer> sources,
                              Map<String, Integer> positions,
                              Map<String, Candidate> representative) {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < list.size(); i++) {
            Candidate candidate = list.get(i);
            if (candidate == null || !seen.add(candidate.key())) {
                continue;
            }
            // 通道内同一 key 的首次出现就是该轮的最好名次
            bestRank.merge(candidate.key(), i, Math::min);
            sources.putIfAbsent(candidate.key(), source);
            positions.putIfAbsent(candidate.key(), i);
            representative.putIfAbsent(candidate.key(), candidate);
        }
    }
}
