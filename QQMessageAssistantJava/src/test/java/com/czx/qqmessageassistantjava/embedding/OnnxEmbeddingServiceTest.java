package com.czx.qqmessageassistantjava.embedding;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 真模型测试：模型未下载时跳过，不阻塞 CI。
 *
 * <p>下载方式见 {@code scripts/fetch-embedding-model.sh}。
 */
@Tag("model")
class OnnxEmbeddingServiceTest {

    private static final Path MODEL_DIR = Path.of("./models/bge-small-zh-v1.5");

    @Test
    void model_produces_normalized_vectors_and_similarity() {
        Assumptions.assumeTrue(Files.exists(MODEL_DIR.resolve("model.onnx")),
                "模型未下载，跳过真模型测试");

        var svc = OnnxEmbeddingService.create(propsWithModelDir(MODEL_DIR));

        float[] a = svc.embed("小明的生日是哪天");
        float[] b = svc.embed("小明出生日期");
        float[] c = svc.embed("今天会下雨吗");

        assertThat(a).hasSize(512);
        assertThat(norm(a)).isCloseTo(1.0, within(1e-5));
        assertThat(cos(a, b)).isGreaterThan(0.7f);
        assertThat(cos(a, c)).isLessThan(0.4f);
    }

    @Test
    void missing_model_degrades_without_throwing() {
        var svc = OnnxEmbeddingService.create(propsWithModelDir(Path.of("./no-such-dir")));
        assertThat(svc.available()).isFalse();
    }

    /**
     * Review Focus 5：模型目录配错不能让应用起不来。
     * 非法路径（含 NUL）与 null 都必须在构造期被降级处理，而不是抛到容器启动里。
     */
    @Test
    void malformed_model_dir_degrades_without_throwing() {
        EmbeddingProperties broken = propsWithModelDir(Path.of("./models/placeholder"));
        broken.setModelDir("bad\u0000path");
        assertThat(OnnxEmbeddingService.create(broken).available()).isFalse();

        EmbeddingProperties missing = propsWithModelDir(Path.of("./models/placeholder"));
        missing.setModelDir(null);
        assertThat(OnnxEmbeddingService.create(missing).available()).isFalse();
    }

    @Test
    void corrupt_model_file_degrades_without_throwing() throws Exception {
        Path dir = Files.createTempDirectory("bad-model");
        Files.writeString(dir.resolve("vocab.txt"), "[PAD]\n[UNK]\n[CLS]\n[SEP]\n");
        Files.write(dir.resolve("model.onnx"), new byte[]{1, 2, 3});

        var svc = OnnxEmbeddingService.create(propsWithModelDir(dir));
        assertThat(svc.available()).isFalse();
    }

    private static EmbeddingProperties propsWithModelDir(Path dir) {
        EmbeddingProperties props = new EmbeddingProperties();
        props.setEnabled(true);
        props.setMode("local");
        props.setModelDir(dir.toString());
        props.setModelId("bge-small-zh-v1.5");
        props.setDim(512);
        return props;
    }

    private static double norm(float[] v) {
        double sum = 0;
        for (float x : v) {
            sum += (double) x * x;
        }
        return Math.sqrt(sum);
    }

    private static float cos(float[] a, float[] b) {
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        return (float) (dot / (Math.sqrt(na) * Math.sqrt(nb)));
    }
}
