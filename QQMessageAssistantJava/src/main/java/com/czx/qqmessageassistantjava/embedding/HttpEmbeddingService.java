package com.czx.qqmessageassistantjava.embedding;

import com.czx.qqmessageassistantjava.common.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * OpenAI 兼容的 {@code POST /embeddings} 向量服务（{@code app.embedding.mode=http}）。
 *
 * <p>给不想在 Java 进程内跑 ONNX 的部署留的口子，默认不用。失败抛
 * {@link BusinessException}，由调用方降级；api-key 只出现在请求头，不写日志。
 */
@Component
@ConditionalOnProperty(prefix = "app.embedding", name = "mode", havingValue = "http")
public class HttpEmbeddingService implements EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(HttpEmbeddingService.class);

    private static final ParameterizedTypeReference<Map<String, Object>> RESPONSE =
            new ParameterizedTypeReference<>() {
            };

    private final EmbeddingProperties properties;
    private final String modelId;
    private final int dim;
    private final RestClient restClient;

    @Autowired
    public HttpEmbeddingService(EmbeddingProperties properties) {
        this(properties, buildClient(properties));
    }

    HttpEmbeddingService(EmbeddingProperties properties, RestClient restClient) {
        this.properties = properties;
        this.modelId = resolveModelId(properties);
        this.dim = properties.getDim();
        this.restClient = restClient;
    }

    @Override
    public boolean available() {
        return properties.isEnabled() && restClient != null;
    }

    @Override
    public String modelId() {
        return modelId;
    }

    @Override
    public int dim() {
        return dim;
    }

    @Override
    public float[] embed(String text) {
        if (!available()) {
            throw new BusinessException("语义检索未启用：HTTP 向量端点未配置");
        }
        Objects.requireNonNull(text, "text");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelId);
        body.put("input", text);

        Map<String, Object> response;
        try {
            response = restClient.post()
                    .uri("/embeddings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(RESPONSE);
        } catch (Exception e) {
            log.error("调用 HTTP 向量服务失败 model={}", modelId, e);
            throw new BusinessException("调用向量服务失败：" + e.getMessage());
        }

        float[] vector = parseEmbedding(response);
        if (vector.length != dim) {
            throw new BusinessException("向量服务返回维度 " + vector.length + " 与配置 " + dim + " 不符");
        }
        return l2Normalize(vector);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        Objects.requireNonNull(texts, "texts");
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (String text : texts) {
            vectors.add(embed(text));
        }
        return vectors;
    }

    private static float[] parseEmbedding(Map<String, Object> response) {
        if (response == null) {
            throw new BusinessException("向量服务返回空响应");
        }
        Object data = response.get("data");
        if (!(data instanceof List<?> list) || list.isEmpty() || !(list.get(0) instanceof Map<?, ?> first)) {
            throw new BusinessException("向量服务返回格式异常：缺少 data[0]");
        }
        Object embedding = first.get("embedding");
        if (!(embedding instanceof List<?> values) || values.isEmpty()) {
            throw new BusinessException("向量服务返回格式异常：缺少 data[0].embedding");
        }
        float[] vector = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            Object value = values.get(i);
            if (!(value instanceof Number number)) {
                throw new BusinessException("向量服务返回格式异常：embedding 含非数字");
            }
            vector[i] = number.floatValue();
        }
        return vector;
    }

    private static float[] l2Normalize(float[] vector) {
        double sum = 0;
        for (float v : vector) {
            sum += (double) v * v;
        }
        double norm = Math.sqrt(sum);
        if (norm == 0) {
            log.warn("HTTP 向量服务返回零向量，无法归一化");
            return vector;
        }
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) (vector[i] / norm);
        }
        return vector;
    }

    private static String resolveModelId(EmbeddingProperties properties) {
        String httpModel = properties.getHttp() == null ? null : properties.getHttp().getModel();
        return httpModel == null || httpModel.isBlank() ? properties.getModelId() : httpModel;
    }

    private static RestClient buildClient(EmbeddingProperties properties) {
        if (!properties.isEnabled()) {
            log.info("语义检索未启用：app.embedding.enabled=false");
            return null;
        }
        EmbeddingProperties.Http http = properties.getHttp();
        if (http == null || http.getBaseUrl() == null || http.getBaseUrl().isBlank()) {
            log.warn("语义检索未启用：app.embedding.http.base-url 为空");
            return null;
        }
        RestClient.Builder builder = RestClient.builder().baseUrl(http.getBaseUrl().trim());
        if (http.getApiKey() != null && !http.getApiKey().isBlank()) {
            builder.defaultHeader("Authorization", "Bearer " + http.getApiKey());
        }
        return builder.build();
    }
}
