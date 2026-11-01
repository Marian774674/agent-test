package com.czx.qqmessageassistantjava.retrieval;

import java.util.Set;

/**
 * 七路召回入口。
 *
 * <p>每个通道独立查询、独立失败：跑了的通道一定出现在结果里（可能是空列表），
 * 抛异常的通道整路省略（上游据此把 {@code scope.channels} 记为 {@code null}）。
 */
public interface RetrievalService {

    /**
     * 执行七路召回。
     *
     * @param plan        查询计划
     * @param excludeKeys 已在之前轮次出现过的候选 key（{@code type:id}）；放入结果前过滤
     * @return 各通道的候选，通道顺序固定，通道内列表顺序即该路名次
     */
    RetrievalResult retrieve(QueryPlan plan, Set<String> excludeKeys);
}
