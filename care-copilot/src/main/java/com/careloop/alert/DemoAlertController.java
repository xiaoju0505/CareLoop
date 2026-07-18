package com.careloop.alert;

import com.careloop.feishu.FeishuChatService;
import com.careloop.feishu.FeishuMessageService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/demo")
public class DemoAlertController {

    private final JdbcTemplate jdbcTemplate;
    private final FeishuMessageService feishuMessageService;
    private final FeishuChatService feishuChatService;

    public DemoAlertController(JdbcTemplate jdbcTemplate,
                               FeishuMessageService feishuMessageService,
                               FeishuChatService feishuChatService) {
        this.jdbcTemplate = jdbcTemplate;
        this.feishuMessageService = feishuMessageService;
        this.feishuChatService = feishuChatService;
    }

    /** 列出机器人所在的群，方便复制 chat_id */
    @GetMapping("/chats")
    public Map<String, Object> listChats() {
        List<Map<String, String>> chats = feishuChatService.listBotChats();
        return Map.of(
                "count", chats.size(),
                "chats", chats,
                "tip", "复制目标群的 chatId，调用 /api/demo/send-alert-card"
        );
    }

    /**
     * 演示：创建一条黄警，并向指定飞书用户/群发送交互卡片。
     * Body 示例：
     * {
     *   "receiveIdType": "chat_id",
     *   "receiveId": "oc_xxx",
     *   "patientName": "张女士",
     *   "level": "YELLOW",
     *   "reason": "术后第5天伤口渗液增多，体温37.8℃"
     * }
     */
    @PostMapping("/send-alert-card")
    public Map<String, Object> sendAlertCard(@RequestBody Map<String, String> req) {
        String receiveIdType = req.getOrDefault("receiveIdType", "chat_id");
        String receiveId = req.getOrDefault("receiveId", req.getOrDefault("openId", ""));
        if (receiveId.isBlank()) {
            throw new IllegalArgumentException("receiveId/openId 不能为空，先调用 GET /api/demo/chats 获取");
        }
        String patientName = req.getOrDefault("patientName", "张女士");
        String level = req.getOrDefault("level", "YELLOW");
        String reason = req.getOrDefault("reason", "术后随访出现异常，请医生确认");

        Long patientId = jdbcTemplate.query(
                "SELECT id FROM patient ORDER BY id ASC LIMIT 1",
                rs -> rs.next() ? rs.getLong(1) : null
        );
        if (patientId == null) {
            throw new IllegalStateException("库中没有患者，请先执行 schema.sql");
        }

        jdbcTemplate.update(
                """
                        INSERT INTO alert_event(patient_id, level, reason, status)
                        VALUES (?, ?, ?, 'OPEN')
                        """,
                patientId, level.toUpperCase(), reason
        );
        Long alertId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        String messageId = feishuMessageService.sendAlertCard(
                receiveIdType, receiveId, patientName, level, reason, alertId
        );

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("alertId", alertId);
        result.put("messageId", messageId);
        result.put("tip", "请在飞书群里查看机器人卡片；点按钮需先配置公网回调");
        return result;
    }
}
