package com.czx.qqmessageassistantjava.embedding;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxJavaType;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import com.czx.qqmessageassistantjava.common.BusinessException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 进程内 ONNX 向量服务（BGE-small-zh-v1.5，512 维，CLS pooling，L2 归一化）。
 *
 * <p>模型缺失或损坏时**不抛异常、不影响启动**：{@link #available()} 返回 false，
 * {@link #embed(String)} 抛 {@link BusinessException}，由调用方关闭语义通道并降级。
 *
 * <p>{@code app.embedding.mode} 缺省即 local，因此两种 mode 下始终恰好存在一个
 * {@link EmbeddingService} bean。
 */
@Component
@ConditionalOnProperty(prefix = "app.embedding", name = "mode", havingValue = "local", matchIfMissing = true)
public class OnnxEmbeddingService implements EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(OnnxEmbeddingService.class);

    /** BERT 上限 512 token（含 [CLS]/[SEP]）。 */
    static final int MAX_TOKENS = 512;

    private static final String INPUT_IDS = "input_ids";
    private static final String ATTENTION_MASK = "attention_mask";
    private static final String TOKEN_TYPE_IDS = "token_type_ids";
    private static final String LAST_HIDDEN_STATE = "last_hidden_state";
    private static final String UNAVAILABLE_MESSAGE = "语义检索未启用：模型未就绪";

    private final String modelId;
    private final int dim;
    private final boolean available;
    private final OrtEnvironment environment;
    private final OrtSession session;
    private final WordPieceTokenizer tokenizer;
    private final String inputIdsName;
    private final String attentionMaskName;
    private final boolean feedTokenTypeIds;
    private final String preferredOutput;

    /** Spring 构造入口；内部与 {@link #create(EmbeddingProperties)} 走同一条加载路径。 */
    @Autowired
    public OnnxEmbeddingService(EmbeddingProperties properties) {
        this(load(properties));
    }

    private OnnxEmbeddingService(Loaded loaded) {
        this.modelId = loaded.modelId;
        this.dim = loaded.dim;
        this.available = loaded.available();
        this.environment = loaded.environment;
        this.session = loaded.session;
        this.tokenizer = loaded.tokenizer;
        this.inputIdsName = loaded.inputIdsName;
        this.attentionMaskName = loaded.attentionMaskName;
        this.feedTokenTypeIds = loaded.feedTokenTypeIds;
        this.preferredOutput = loaded.preferredOutput;
    }

    /** 供测试（无 Spring 容器）或脚本直接构造。 */
    public static OnnxEmbeddingService create(EmbeddingProperties properties) {
        return new OnnxEmbeddingService(properties);
    }

    @Override
    public boolean available() {
        return available;
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
        if (!available) {
            throw new BusinessException(UNAVAILABLE_MESSAGE);
        }
        Objects.requireNonNull(text, "text");

        List<Integer> ids = tokenizer.encode(text, MAX_TOKENS);
        int length = ids.size();
        long[] inputIds = new long[length];
        for (int i = 0; i < length; i++) {
            inputIds[i] = ids.get(i);
        }
        long[] attentionMask = new long[length];
        Arrays.fill(attentionMask, 1L);
        long[] shape = {1, length};

        Map<String, OnnxTensor> feeds = new LinkedHashMap<>();
        OnnxTensor idsTensor = null;
        OnnxTensor maskTensor = null;
        OnnxTensor typesTensor = null;
        try {
            idsTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(inputIds), shape);
            feeds.put(inputIdsName, idsTensor);

            if (attentionMaskName != null) {
                maskTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(attentionMask), shape);
                feeds.put(attentionMaskName, maskTensor);
            }
            if (feedTokenTypeIds) {
                typesTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(new long[length]), shape);
                feeds.put(TOKEN_TYPE_IDS, typesTensor);
            }

            try (OrtSession.Result result = session.run(feeds)) {
                return extractClsVector(result);
            }
        } catch (OrtException e) {
            throw new BusinessException("语义向量计算失败：" + e.getMessage());
        } finally {
            closeQuietly(idsTensor);
            closeQuietly(maskTensor);
            closeQuietly(typesTensor);
        }
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

    @PreDestroy
    public void close() {
        if (session != null) {
            closeQuietly(session);
        }
    }

    /** 取 batch=0、token=0（CLS）的隐藏状态，并 L2 归一化。 */
    private float[] extractClsVector(OrtSession.Result result) {
        OnnxTensor tensor = preferredOutput == null ? null
                : result.get(preferredOutput).filter(OnnxTensor.class::isInstance)
                        .map(OnnxTensor.class::cast).orElse(null);

        if (tensor == null) {
            for (Map.Entry<String, OnnxValue> entry : result) {
                if (entry.getValue() instanceof OnnxTensor candidate) {
                    long[] candidateShape = candidate.getInfo().getShape();
                    if (candidateShape.length == 2 || candidateShape.length == 3) {
                        tensor = candidate;
                        break;
                    }
                }
            }
        }
        if (tensor == null) {
            throw new BusinessException("语义向量计算失败：模型没有 2 维/3 维浮点输出");
        }
        if (tensor.getInfo().type != OnnxJavaType.FLOAT) {
            throw new BusinessException("语义向量计算失败：模型输出不是 float32");
        }

        long[] outputShape = tensor.getInfo().getShape();
        int outputDim = (int) outputShape[outputShape.length - 1];
        if (outputDim != dim) {
            throw new BusinessException("语义向量计算失败：模型输出维度 " + outputDim + " 与配置 " + dim + " 不符");
        }

        FloatBuffer buffer = tensor.getFloatBuffer();
        float[] vector = new float[dim];
        for (int i = 0; i < dim; i++) {
            vector[i] = buffer.get(i);
        }
        return l2Normalize(vector);
    }

    private static float[] l2Normalize(float[] vector) {
        double sum = 0;
        for (float v : vector) {
            sum += (double) v * v;
        }
        double norm = Math.sqrt(sum);
        if (norm == 0) {
            log.warn("模型输出零向量，无法归一化");
            return vector;
        }
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) (vector[i] / norm);
        }
        return vector;
    }

    private static void closeQuietly(OnnxTensor tensor) {
        if (tensor != null) {
            try {
                tensor.close();
            } catch (Exception e) {
                log.debug("关闭 ONNX 输入张量失败", e);
            }
        }
    }

    /** 加载结果；失败时只带降级信息，不持有任何资源。 */
    private record Loaded(String modelId, int dim, boolean available, OrtEnvironment environment,
                          OrtSession session, WordPieceTokenizer tokenizer, String inputIdsName,
                          String attentionMaskName, boolean feedTokenTypeIds, String preferredOutput) {

        static Loaded unavailable(String modelId, int dim) {
            return new Loaded(modelId, dim, false, null, null, null, INPUT_IDS, null, false, null);
        }
    }

    private static Loaded load(EmbeddingProperties properties) {
        String modelId = properties.getModelId();
        int dim = properties.getDim();

        if (!properties.isEnabled()) {
            log.info("语义检索未启用：app.embedding.enabled=false");
            return Loaded.unavailable(modelId, dim);
        }

        Path modelPath = null;
        Path vocabPath = null;
        try {
            // 路径解析也放进 try：配错目录（非法字符、null）只能是「语义不可用」，
            // 不能把异常抛到容器启动里——model-dir 写错也要能起来（Review Focus 5）。
            Path modelDir = Path.of(properties.getModelDir());
            modelPath = modelDir.resolve("model.onnx");
            vocabPath = modelDir.resolve("vocab.txt");
            if (!Files.isRegularFile(modelPath) || !Files.isRegularFile(vocabPath)) {
                log.warn("语义检索未启用：模型文件缺失（model={}, vocab={}），按降级处理",
                        modelPath.toAbsolutePath(), vocabPath.toAbsolutePath());
                return Loaded.unavailable(modelId, dim);
            }

            WordPieceTokenizer tokenizer = new WordPieceTokenizer(vocabPath);
            OrtEnvironment environment = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            OrtSession session;
            try {
                session = environment.createSession(modelPath.toString(), options);
            } finally {
                options.close();
            }

            Set<String> inputNames = session.getInputNames();
            String inputIdsName = inputNames.contains(INPUT_IDS)
                    ? INPUT_IDS
                    : inputNames.stream()
                            .filter(name -> !ATTENTION_MASK.equals(name) && !TOKEN_TYPE_IDS.equals(name))
                            .findFirst()
                            .orElse(INPUT_IDS);
            String attentionMaskName = inputNames.contains(ATTENTION_MASK) ? ATTENTION_MASK : null;
            boolean feedTokenTypeIds = inputNames.contains(TOKEN_TYPE_IDS);

            String preferredOutput = session.getOutputNames().contains(LAST_HIDDEN_STATE)
                    ? LAST_HIDDEN_STATE : null;
            String dimensionError = validateOutputDim(session, preferredOutput, dim);
            if (dimensionError != null) {
                closeQuietly(session);
                log.warn("语义检索未启用：{}（path={}）", dimensionError, modelPath.toAbsolutePath());
                return Loaded.unavailable(modelId, dim);
            }

            log.info("语义检索模型已加载：path={}, dim={}, inputs={}, outputs={}",
                    modelPath.toAbsolutePath(), dim, inputNames, session.getOutputNames());
            return new Loaded(modelId, dim, true, environment, session, tokenizer,
                    inputIdsName, attentionMaskName, feedTokenTypeIds, preferredOutput);
        } catch (Throwable t) {
            // 包括 UnsatisfiedLinkError：native 库缺失也不能拖垮启动
            log.warn("语义检索未启用：模型加载失败（path={}）",
                    modelPath == null ? properties.getModelDir() : modelPath, t);
            return Loaded.unavailable(modelId, dim);
        }
    }

    /** 找到用于 CLS pooling 的输出；已知静态维度且与配置不符时返回错误说明。 */
    private static String validateOutputDim(OrtSession session, String preferredOutput, int expectedDim) {
        try {
            Map<String, NodeInfo> outputs = session.getOutputInfo();
            NodeInfo preferred = preferredOutput == null ? null : outputs.get(preferredOutput);
            if (preferred != null && preferred.getInfo() instanceof TensorInfo info) {
                return dimensionMismatch(info, expectedDim);
            }
            for (NodeInfo nodeInfo : outputs.values()) {
                if (nodeInfo.getInfo() instanceof TensorInfo info
                        && (info.getShape().length == 2 || info.getShape().length == 3)) {
                    return dimensionMismatch(info, expectedDim);
                }
            }
            return "模型没有可用的 2 维/3 维输出";
        } catch (OrtException e) {
            return "读取模型输出信息失败：" + e.getMessage();
        }
    }

    private static String dimensionMismatch(TensorInfo info, int expectedDim) {
        long[] shape = info.getShape();
        if (shape.length == 0) {
            return "模型输出缺少维度信息";
        }
        long lastDim = shape[shape.length - 1];
        if (lastDim > 0 && lastDim != expectedDim) {
            return "模型输出维度 " + lastDim + " 与配置 app.embedding.dim=" + expectedDim + " 不符";
        }
        return null;
    }

    private static void closeQuietly(OrtSession session) {
        try {
            session.close();
        } catch (Exception e) {
            log.debug("关闭 ONNX 会话失败", e);
        }
    }
}
