package com.czx.qqmessageassistantjava.embedding;

import java.util.List;

/**
 * 文本向量化服务。实现有两种：本地 ONNX（默认）与 OpenAI 兼容的 HTTP 端点。
 *
 * <p>{@link #available()} 为 {@code false} 时，语义检索通道关闭，调用方应降级到其余召回通道；
 * 此时 {@link #embed(String)} 抛 {@link com.czx.qqmessageassistantjava.common.BusinessException}。
 */
public interface EmbeddingService {

    /** 服务是否就绪（模型已加载或 HTTP 端点已配置）。 */
    boolean available();

    /** 模型标识，落库到 {@code message_embedding.model}；换模型即全量重建。 */
    String modelId();

    /** 向量维度。 */
    int dim();

    /** 单条文本向量；返回 L2 归一化后的向量。 */
    float[] embed(String text);

    /** 批量向量化。本期逐条实现，不做 padding 批处理。 */
    List<float[]> embedBatch(List<String> texts);
}
