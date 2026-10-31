package com.czx.qqmessageassistantjava.retrieval;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 一次召回需要的东西：认出来的人、关键词、改写后的检索串与时间提示。
 *
 * <p>{@code subjects} 是已经解析成 {@code sender_id} 的主体（认人由上层负责）；
 * {@code entities} 是原始人名，用于「名字出现」通道；{@code queries} 是原问题 + 改写，
 * 词法通道每条都跑，语义通道只用前 3 条。时间字段本任务不参与过滤，
 * 由后续 RRF 融合做软分段。所有集合都允许为空，空集合表示该路不查。
 */
public record QueryPlan(
        Set<Long> subjects,
        List<String> entities,
        List<String> keywords,
        List<String> queries,
        LocalDateTime timeFrom,
        LocalDateTime timeTo) {

    public QueryPlan {
        subjects = subjects == null ? Set.of()
                : subjects.stream().filter(Objects::nonNull)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
        entities = copy(entities);
        keywords = copy(keywords);
        queries = copy(queries);
    }

    /** 去 null 后再拷贝：Dify 的输出偶尔会带 null 元素，构造计划不能因此抛异常。 */
    private static List<String> copy(List<String> values) {
        return values == null ? List.of()
                : values.stream().filter(Objects::nonNull).toList();
    }
}
