package com.czx.qqmessageassistantjava.embedding;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 语义检索配置（prefix {@code app.embedding}）。
 *
 * <p>{@code mode=local} 在进程内跑 ONNX；{@code mode=http} 调 OpenAI 兼容的
 * {@code /embeddings}，给不方便在 Java 里跑模型的部署留口子。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.embedding")
public class EmbeddingProperties {

    /** false 或模型未就绪 → 语义通道关闭，其余六路照常。 */
    private boolean enabled = true;

    /** local | http */
    private String mode = "local";

    /** 相对模块根目录解析（运行目录即 QQMessageAssistantJava）。 */
    private String modelDir = "./models/bge-small-zh-v1.5";

    private String modelId = "bge-small-zh-v1.5";

    private int dim = 512;

    private Http http = new Http();

    @Data
    public static class Http {

        /** 例如 http://127.0.0.1:8081/v1；留空则 HTTP 模式不可用。 */
        private String baseUrl = "";

        /** 不写进日志。 */
        private String apiKey = "";

        private String model = "";
    }
}
