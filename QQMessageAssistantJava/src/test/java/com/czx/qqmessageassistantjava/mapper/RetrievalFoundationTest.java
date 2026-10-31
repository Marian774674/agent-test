package com.czx.qqmessageassistantjava.mapper;

import com.czx.qqmessageassistantjava.entity.MessageEmbedding;
import com.czx.qqmessageassistantjava.entity.MessageLog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 智能检索的数据底座：向量存取、中文全文召回、按名字计数。
 *
 * <p>这三件事分别钉住三个容易退化的点：
 * <ul>
 *   <li>向量 blob 原样往返（float32 小端，长度 = dim*4）；</li>
 *   <li>ngram 全文索引能在中文里命中，且不按 sender 过滤（跨说话人）；</li>
 *   <li>{@code LIKE} 通配符必须转义，否则「50%」会把「50 起」也算进来。</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RetrievalFoundationTest {

    @Autowired
    private MessageEmbeddingMapper mapper;

    @Autowired
    private MessageLogMapper messageLogMapper;

    @Test
    void embedding_roundTrips() {
        MessageEmbedding e = new MessageEmbedding();
        e.setMessageLogId(9_900_001L);
        e.setModel("m1");
        e.setDim(4);
        e.setVector(new byte[]{0, 0, (byte) 0x80, 0x3f, 0, 0, 0, 0x40, 0, 0, 0x40, 0x40, 0, 0, (byte) 0x80, 0x3f});
        mapper.upsert(e);
        MessageEmbedding back = mapper.selectOne(9_900_001L, "m1");
        assertThat(back.getDim()).isEqualTo(4);
        assertThat(back.getVector()).hasSize(16);
    }

    /**
     * InnoDB FULLTEXT 看不到本事务尚未提交的行（实测：同一事务内 MATCH 命中 0，提交后命中 1），
     * 所以这个方法用 {@link Propagation#NOT_SUPPORTED} 脱离测试事务：插入即提交，查完再删干净。
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void fulltext_finds_chinese_without_sender_filter() {
        messageLogMapper.deleteById(9_900_101L);
        messageLogMapper.deleteById(9_900_102L);
        try {
            insertIn(9_900_101L, 100L, "小红", "小明生日是11月11");
            insertIn(9_900_102L, 200L, "小明", "今天好热");
            assertThat(messageLogMapper.searchFulltext("小明生日", 10))
                    .extracting(MessageLog::getId).contains(9_900_101L);
        } finally {
            messageLogMapper.deleteById(9_900_101L);
            messageLogMapper.deleteById(9_900_102L);
        }
    }

    @Test
    void countMentions_escapes_like_wildcards() {
        insertIn(9_900_201L, 100L, "小红", "折扣 50% 起");
        assertThat(messageLogMapper.countMentions("50%")).isEqualTo(1);
        assertThat(messageLogMapper.countMentions("5%0")).isEqualTo(0);
    }

    /**
     * 增量索引任务（Tasks 3/5）依赖这条 SQL 与 record 构造器映射。
     * 空串 / NULL 的消息没有可检索的内容，不应出现在待办里。
     */
    @Test
    void selectMissing_returns_seeded_row() {
        insertIn(9_900_301L, 100L, "小红", "需要向量化的消息");
        insertIn(9_900_302L, 100L, "小红", "");
        insertIn(9_900_303L, 100L, "小红", null);

        List<MessageEmbeddingMapper.MissingMessage> missing = mapper.selectMissing("m1", 10);

        assertThat(missing).extracting(MessageEmbeddingMapper.MissingMessage::id)
                .contains(9_900_301L)
                .doesNotContain(9_900_302L, 9_900_303L);
        assertThat(missing)
                .filteredOn(m -> m.id() == 9_900_301L)
                .extracting(MessageEmbeddingMapper.MissingMessage::content)
                .containsExactly("需要向量化的消息");
    }

    /**
     * 所有 NOT NULL 列都给确定值；{@code dedupe_key} 有唯一约束，按 id 造。
     */
    private void insertIn(long id, long senderId, String senderName, String content) {
        MessageLog row = new MessageLog();
        row.setId(id);
        row.setSenderId(senderId);
        row.setSenderName(senderName);
        row.setChatId(9_900_000L + senderId);
        row.setChatName("测试群" + senderId);
        row.setConversationId("conv-" + id);
        row.setMessageId("msg-" + id);
        row.setDedupeKey("seed-" + id);
        row.setDirection("IN");
        row.setMsgType("text");
        row.setContent(content);
        row.setCreatedAt(LocalDateTime.of(2026, 10, 31, 12, 0));
        messageLogMapper.insert(row);
    }
}
