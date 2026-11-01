package com.czx.qqmessageassistantjava.controller;

import com.czx.qqmessageassistantjava.dto.MemoCreateRequest;
import com.czx.qqmessageassistantjava.entity.Memo;
import com.czx.qqmessageassistantjava.service.MemoService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class MemoControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private MemoService memoService;

    @Test
    void previewDescribesThePlanInChinese() throws Exception {
        LocalDate tomorrow = LocalDate.now().plusDays(1);

        mockMvc.perform(get("/api/memos/reminder-preview")
                        .param("category", "学业")
                        .param("dueTime", tomorrow + "T15:00:00")
                        .param("importance", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].label").value("-1d@20:00"))
                .andExpect(jsonPath("$.data[0].text").value("前一天 20:00"))
                .andExpect(jsonPath("$.data[1].label").value("-1h"))
                .andExpect(jsonPath("$.data[1].text").value("提前 1 小时"));
    }

    @Test
    void previewOfADateOnlyMemoNeverLandsAtNight() throws Exception {
        LocalDate due = LocalDate.now().plusDays(3);

        mockMvc.perform(get("/api/memos/reminder-preview")
                        .param("category", "不存在的类型")
                        .param("dueTime", due.toString())
                        .param("importance", "4"))
                .andExpect(status().isOk())
                // 不认识的类型落到「其他」：只提前 1 小时，锚点是当天 09:00
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].remindAt").value(due + "T08:00:00"));
    }

    @Test
    void previewRejectsUnparseableDueTime() throws Exception {
        mockMvc.perform(get("/api/memos/reminder-preview")
                        .param("category", "学业")
                        .param("dueTime", "下周某个时候"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void remindersCarryChineseOffsetText() throws Exception {
        Memo memo = seed("社交", LocalDateTime.now().plusDays(3).withNano(0));

        mockMvc.perform(get("/api/memos/" + memo.getId() + "/reminders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].offsetLabel").value("-2h"))
                .andExpect(jsonPath("$.data[0].offsetText").value("提前 2 小时"))
                .andExpect(jsonPath("$.data[1].offsetText").value("提前 30 分钟"));
    }

    @Test
    void replanReplacesPendingReminders() throws Exception {
        Memo memo = seed("日常", LocalDateTime.now().plusDays(3).withNano(0));

        mockMvc.perform(post("/api/memos/" + memo.getId() + "/replan"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.status == 'PENDING')].offsetLabel",
                        org.hamcrest.Matchers.contains("-30m")))
                .andExpect(jsonPath("$.data[?(@.status == 'CANCELLED')]",
                        org.hamcrest.Matchers.hasSize(1)));
    }

    @Test
    void replanRejectsUnknownMemo() throws Exception {
        mockMvc.perform(post("/api/memos/99999999/replan"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    private Memo seed(String category, LocalDateTime due) {
        MemoCreateRequest request = new MemoCreateRequest();
        request.setSenderId(9401L);
        request.setTitle("t");
        request.setCategory(category);
        request.setImportance(3);
        request.setDueTime(due);
        return memoService.create(request);
    }
}
