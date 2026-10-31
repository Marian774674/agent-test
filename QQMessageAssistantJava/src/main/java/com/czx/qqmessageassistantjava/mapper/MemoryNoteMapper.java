package com.czx.qqmessageassistantjava.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czx.qqmessageassistantjava.entity.MemoryNote;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

@Mapper
public interface MemoryNoteMapper extends BaseMapper<MemoryNote> {

    /**
     * 召回：任一 keyword 命中 {@code value} 或 {@code predicate}，时间倒序，最多 {@code limit} 条。
     *
     * <p>入参先按 {@link MessageLogMapper#likeEscape(String)} 转义：alias/关键词里出现
     * {@code %}、{@code _} 或反斜杠时不能当成通配符。
     */
    default List<MemoryNote> searchByKeywords(List<String> keywords, int limit) {
        List<String> escaped = keywords == null ? List.of()
                : keywords.stream()
                        .filter(keyword -> keyword != null && !keyword.isBlank())
                        .map(String::trim)
                        .map(MessageLogMapper::likeEscape)
                        .toList();
        if (escaped.isEmpty()) {
            return List.of();
        }
        return searchByKeywordsEscaped(escaped, limit);
    }

    @Select("<script>"
            + "SELECT * FROM memory_note WHERE "
            + "<foreach collection=\"keywords\" item=\"kw\" separator=\" OR \">"
            + "(`value` LIKE CONCAT('%', #{kw}, '%') ESCAPE '\\\\' "
            + "OR `predicate` LIKE CONCAT('%', #{kw}, '%') ESCAPE '\\\\')"
            + "</foreach> "
            + "ORDER BY id DESC LIMIT #{limit}"
            + "</script>")
    List<MemoryNote> searchByKeywordsEscaped(@Param("keywords") List<String> keywords,
                                             @Param("limit") int limit);

    /** 召回：主体在本批 person 里的结构化事实，时间倒序（按 id），最多 {@code limit} 条。 */
    default List<MemoryNote> selectBySubjects(Collection<Long> subjects, int limit) {
        if (subjects == null || subjects.isEmpty()) {
            return List.of();
        }
        return selectBySubjectsIn(subjects, limit);
    }

    @Select("<script>SELECT * FROM memory_note WHERE subject_sender_id IN "
            + "<foreach collection=\"subjects\" item=\"sid\" open=\"(\" separator=\",\" close=\")\">"
            + "#{sid}</foreach> "
            + "ORDER BY id DESC LIMIT #{limit}</script>")
    List<MemoryNote> selectBySubjectsIn(@Param("subjects") Collection<Long> subjects,
                                        @Param("limit") int limit);
}
