package com.czx.qqmessageassistantjava.embedding;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 索引维度来自配置 {@code app.embedding.dim}，而不是探测模型。
 *
 * <p>这样模型缺失/加载失败时应用仍能启动：索引对象照常存在，只是
 * {@code app.embedding} 的语义通道在 {@code EmbeddingJobService.status()} 中报告为不可用。
 */
@Configuration
public class EmbeddingIndexConfig {

    @Bean
    public EmbeddingIndex embeddingIndex(EmbeddingProperties properties) {
        return new EmbeddingIndex(properties.getDim());
    }
}
