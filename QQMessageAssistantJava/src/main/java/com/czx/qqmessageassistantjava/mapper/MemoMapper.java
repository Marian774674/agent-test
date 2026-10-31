package com.czx.qqmessageassistantjava.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czx.qqmessageassistantjava.entity.Memo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface MemoMapper extends BaseMapper<Memo> {

    /**
     * 召回：任一 keyword 命中标题或正文，时间倒序，最多 {@code limit} 条。
     * 入参按 {@link MessageLogMapper#likeEscape(String)} 转义，保持与全文/计数一致的 LIKE 语义。
     */
    default List<Memo> searchByKeywords(List<String> keywords, int limit) {
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
            + "SELECT * FROM memo WHERE is_deleted = 0 AND ("
            + "<foreach collection=\"keywords\" item=\"kw\" separator=\" OR \">"
            + "(title LIKE CONCAT('%', #{kw}, '%') ESCAPE '\\\\' "
            + "OR content LIKE CONCAT('%', #{kw}, '%') ESCAPE '\\\\')"
            + "</foreach>) "
            + "ORDER BY id DESC LIMIT #{limit}"
            + "</script>")
    List<Memo> searchByKeywordsEscaped(@Param("keywords") List<String> keywords,
                                       @Param("limit") int limit);
}
