package com.czx.qqmessageassistantjava.embedding;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WordPieceTokenizerTest {

    private static final Path VOCAB = Path.of("src/test/resources/embedding/vocab-fixture.txt");

    private WordPieceTokenizer tokenizer;

    @BeforeEach
    void setUp() {
        tokenizer = new WordPieceTokenizer(VOCAB);
    }

    @Test
    void chinese_chars_become_single_tokens() {
        assertThat(tokenizer.encode("小明", 16))
                .containsExactly(tokenizer.clsId(), tokenizer.tokenId("小"), tokenizer.tokenId("明"), tokenizer.sepId());
    }

    @Test
    void long_input_is_truncated_to_max_len() {
        var ids = tokenizer.encode("小明生日你好".repeat(50), 8);
        assertThat(ids).hasSize(8);
        assertThat(ids.get(0)).isEqualTo(tokenizer.clsId());
        assertThat(ids.get(7)).isEqualTo(tokenizer.sepId());
    }

    @Test
    void unknown_char_falls_back_to_unk() {
        assertThat(tokenizer.encode("龘", 8)).contains(tokenizer.unkId());
    }

    @Test
    void special_token_ids_are_read_from_vocab_file() {
        assertThat(tokenizer.clsId()).isEqualTo(2);
        assertThat(tokenizer.sepId()).isEqualTo(3);
        assertThat(tokenizer.unkId()).isEqualTo(1);
        assertThat(tokenizer.padId()).isEqualTo(0);
        assertThat(tokenizer.vocabSize()).isEqualTo(15);
        assertThat(tokenizer.tokenId("日")).isEqualTo(7);
    }

    @Test
    void max_len_shorter_than_three_is_rejected() {
        assertThatThrownBy(() -> tokenizer.encode("小明", 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blank_text_yields_only_special_tokens() {
        assertThat(tokenizer.encode("  ", 8))
                .containsExactly(tokenizer.clsId(), tokenizer.sepId());
    }
}
