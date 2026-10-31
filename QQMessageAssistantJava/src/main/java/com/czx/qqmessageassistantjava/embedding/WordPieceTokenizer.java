package com.czx.qqmessageassistantjava.embedding;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 纯 Java 实现的 BERT BasicTokenizer + WordPiece 分词器，读取 HuggingFace 的 {@code vocab.txt}
 * （每行一个词元，行号即 id），供 BGE-small-zh 之类的 ONNX 模型使用。
 *
 * <p>规则：NFKC 归一化 + 拉丁字母小写；按空白/标点切分，CJK 字符逐字成词；
 * 每个词做贪心最长匹配（续接片段带 {@code ##} 前缀），未命中整词退化为 {@code [UNK]}；
 * 最后用 {@code [CLS]} … {@code [SEP]} 包裹。
 */
public class WordPieceTokenizer {

    private static final String PAD_TOKEN = "[PAD]";
    private static final String UNK_TOKEN = "[UNK]";
    private static final String CLS_TOKEN = "[CLS]";
    private static final String SEP_TOKEN = "[SEP]";
    private static final String CONTINUATION_PREFIX = "##";
    private static final int MAX_CHARS_PER_WORD = 100;

    private final Map<String, Integer> vocab;
    private final int clsId;
    private final int sepId;
    private final int unkId;

    public WordPieceTokenizer(Path vocabTxt) {
        this.vocab = loadVocab(Objects.requireNonNull(vocabTxt, "vocabTxt"));
        this.clsId = requireSpecialToken(CLS_TOKEN);
        this.sepId = requireSpecialToken(SEP_TOKEN);
        this.unkId = requireSpecialToken(UNK_TOKEN);
    }

    /**
     * 将文本编码为 token id 序列，首尾分别是 {@code [CLS]} 与 {@code [SEP]}。
     *
     * @param maxLen 总长度上限（含首尾特殊词元），至少为 3；超长时保留 {@code [CLS]}、
     *               前 {@code maxLen - 2} 个内容词元与 {@code [SEP]}
     */
    public List<Integer> encode(String text, int maxLen) {
        if (maxLen < 3) {
            throw new IllegalArgumentException("maxLen 至少为 3（含 [CLS] 与 [SEP]）: " + maxLen);
        }
        Objects.requireNonNull(text, "text");

        List<Integer> content = new ArrayList<>();
        for (String word : basicTokenize(text)) {
            wordPiece(word, content);
        }

        int budget = maxLen - 2;
        List<Integer> ids = new ArrayList<>(Math.min(content.size(), budget) + 2);
        ids.add(clsId);
        ids.addAll(content.subList(0, Math.min(content.size(), budget)));
        ids.add(sepId);
        return ids;
    }

    public int tokenId(String token) {
        Integer id = vocab.get(Objects.requireNonNull(token, "token"));
        if (id == null) {
            throw new IllegalArgumentException("词表中不存在该词元: " + token);
        }
        return id;
    }

    public int clsId() {
        return clsId;
    }

    public int sepId() {
        return sepId;
    }

    public int unkId() {
        return unkId;
    }

    public int padId() {
        return requireSpecialToken(PAD_TOKEN);
    }

    public int vocabSize() {
        return vocab.size();
    }

    private static Map<String, Integer> loadVocab(Path path) {
        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取词表失败: " + path, e);
        }

        int lineCount = lines.size();
        while (lineCount > 0 && lines.get(lineCount - 1).isEmpty()) {
            lineCount--;
        }

        Map<String, Integer> map = new HashMap<>(Math.max(16, lineCount * 2));
        for (int i = 0; i < lineCount; i++) {
            String token = lines.get(i);
            if (i == 0 && !token.isEmpty() && token.charAt(0) == 0xFEFF) {
                token = token.substring(1);
            }
            map.putIfAbsent(token, i);
        }
        return map;
    }

    private int requireSpecialToken(String token) {
        Integer id = vocab.get(token);
        if (id == null) {
            throw new IllegalStateException("词表缺少特殊词元: " + token);
        }
        return id;
    }

    /** BERT BasicTokenizer 的简化版：空白切分、标点独立成词、CJK 逐字成词。 */
    private static List<String> basicTokenize(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        List<String> words = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (int codePoint : normalized.codePoints().toArray()) {
            if (isWhitespace(codePoint)) {
                flush(current, words);
            } else if (isCjk(codePoint) || isPunctuation(codePoint)) {
                flush(current, words);
                words.add(new String(Character.toChars(codePoint)));
            } else {
                current.appendCodePoint(codePoint);
            }
        }
        flush(current, words);
        return words;
    }

    private static void flush(StringBuilder current, List<String> words) {
        if (!current.isEmpty()) {
            words.add(current.toString());
            current.setLength(0);
        }
    }

    /** 贪心最长匹配；整词无法完整切分时，该词整体退化为一个 {@code [UNK]}（同 BERT 原始实现）。 */
    private void wordPiece(String word, List<Integer> out) {
        String[] chars = word.codePoints()
                .mapToObj(cp -> new String(Character.toChars(cp)))
                .toArray(String[]::new);
        int length = chars.length;
        if (length > MAX_CHARS_PER_WORD) {
            out.add(unkId);
            return;
        }

        List<Integer> pieces = new ArrayList<>();
        int start = 0;
        while (start < length) {
            int end = length;
            String piece = null;
            while (start < end) {
                String sub = String.join("", Arrays.copyOfRange(chars, start, end));
                String candidate = start > 0 ? CONTINUATION_PREFIX + sub : sub;
                if (vocab.containsKey(candidate)) {
                    piece = candidate;
                    break;
                }
                end--;
            }
            if (piece == null) {
                out.add(unkId);
                return;
            }
            pieces.add(vocab.get(piece));
            start = end;
        }
        out.addAll(pieces);
    }

    private static boolean isWhitespace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    /** 与 BERT 的 {@code _is_chinese_char} 范围一致。 */
    private static boolean isCjk(int codePoint) {
        return (codePoint >= 0x4E00 && codePoint <= 0x9FFF)
                || (codePoint >= 0x3400 && codePoint <= 0x4DBF)
                || (codePoint >= 0x20000 && codePoint <= 0x2A6DF)
                || (codePoint >= 0x2A700 && codePoint <= 0x2B73F)
                || (codePoint >= 0x2B740 && codePoint <= 0x2B81F)
                || (codePoint >= 0x2B820 && codePoint <= 0x2CEAF)
                || (codePoint >= 0xF900 && codePoint <= 0xFAFF)
                || (codePoint >= 0x2F800 && codePoint <= 0x2FA1F);
    }

    /** 与 BERT 的 {@code _is_punctuation} 一致：ASCII 符号区 + Unicode 标点类别。 */
    private static boolean isPunctuation(int codePoint) {
        if ((codePoint >= 33 && codePoint <= 47)
                || (codePoint >= 58 && codePoint <= 64)
                || (codePoint >= 91 && codePoint <= 96)
                || (codePoint >= 123 && codePoint <= 126)) {
            return true;
        }
        return switch (Character.getType(codePoint)) {
            case Character.CONNECTOR_PUNCTUATION,
                 Character.DASH_PUNCTUATION,
                 Character.START_PUNCTUATION,
                 Character.END_PUNCTUATION,
                 Character.INITIAL_QUOTE_PUNCTUATION,
                 Character.FINAL_QUOTE_PUNCTUATION,
                 Character.OTHER_PUNCTUATION -> true;
            default -> false;
        };
    }
}
