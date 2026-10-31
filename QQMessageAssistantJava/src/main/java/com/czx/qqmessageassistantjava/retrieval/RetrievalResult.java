package com.czx.qqmessageassistantjava.retrieval;

import java.util.List;
import java.util.Map;

/**
 * 七路通道的召回结果。每个通道内的 {@link Candidate} 列表顺序即该通道的内部名次（下标 0 为第 1 名）。
 */
public record RetrievalResult(Map<Channel, List<Candidate>> hits) {
}
