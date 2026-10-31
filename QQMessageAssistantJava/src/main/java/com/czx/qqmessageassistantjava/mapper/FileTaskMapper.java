package com.czx.qqmessageassistantjava.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.czx.qqmessageassistantjava.entity.FileTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface FileTaskMapper extends BaseMapper<FileTask> {

    /**
     * 召回：任一 keyword 命中文件名，时间倒序，最多 {@code limit} 条。
     * 入参按 {@link MessageLogMapper#likeEscape(String)} 转义。
     */
    default List<FileTask> searchByKeywords(List<String> keywords, int limit) {
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
            + "SELECT * FROM file_task WHERE "
            + "<foreach collection=\"keywords\" item=\"kw\" separator=\" OR \">"
            + "file_name LIKE CONCAT('%', #{kw}, '%') ESCAPE '\\\\'"
            + "</foreach> "
            + "ORDER BY id DESC LIMIT #{limit}"
            + "</script>")
    List<FileTask> searchByKeywordsEscaped(@Param("keywords") List<String> keywords,
                                           @Param("limit") int limit);
}
