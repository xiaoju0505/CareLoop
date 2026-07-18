package com.careloop.feishu;

import com.careloop.alert.AlertActionService;
import com.careloop.care.CarePlanEditService;
import com.careloop.caseintake.FeishuCaseIntakeService;
import com.careloop.doctor.DoctorQueryService;
import com.careloop.ledger.PatientLedgerService;
import com.careloop.patient.PatientNotifyService;
import com.careloop.session.FeishuDoctorSessionStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 飞书群消息路由：优先处理「改计划 / 回复患者」会话，再走病例建档。
 */
@Service
public class FeishuDoctorMessageRouter {

    private static final Logger log = LoggerFactory.getLogger(FeishuDoctorMessageRouter.class);

    private final FeishuDoctorSessionStore sessionStore;
    private final CarePlanEditService carePlanEditService;
    private final FeishuCaseIntakeService caseIntakeService;
    private final DoctorQueryService doctorQueryService;
    private final PatientNotifyService patientNotifyService;
    private final FeishuMessageService feishuMessageService;
    private final PatientLedgerService patientLedgerService;
    private final AlertActionService alertActionService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public FeishuDoctorMessageRouter(FeishuDoctorSessionStore sessionStore,
                                     CarePlanEditService carePlanEditService,
                                     FeishuCaseIntakeService caseIntakeService,
                                     DoctorQueryService doctorQueryService,
                                     PatientNotifyService patientNotifyService,
                                     FeishuMessageService feishuMessageService,
                                     PatientLedgerService patientLedgerService,
                                     AlertActionService alertActionService,
                                     JdbcTemplate jdbcTemplate,
                                     ObjectMapper objectMapper) {
        this.sessionStore = sessionStore;
        this.carePlanEditService = carePlanEditService;
        this.caseIntakeService = caseIntakeService;
        this.doctorQueryService = doctorQueryService;
        this.patientNotifyService = patientNotifyService;
        this.feishuMessageService = feishuMessageService;
        this.patientLedgerService = patientLedgerService;
        this.alertActionService = alertActionService;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void route(JsonNode event) {
        JsonNode message = event.path("message");
        String chatId = message.path("chat_id").asText("");
        String messageType = message.path("message_type").asText("text");
        String contentJson = message.path("content").asText("{}");
        String text = extractText(messageType, contentJson);

        FeishuDoctorSessionStore.Session session = sessionStore.get(chatId);
        if (session != null && "text".equals(messageType) && text != null && !text.isBlank()) {
            if (session.mode() == FeishuDoctorSessionStore.Mode.EDIT_PLAN) {
                var result = carePlanEditService.applyDoctorText(session.planId(), text, chatId);
                feishuMessageService.sendText("chat_id", chatId, String.valueOf(result.get("message")));
                // 保持会话，方便连续改多条；也可 clear——连续改更友好
                log.info("飞书改计划结果 planId={} ok={}", session.planId(), result.get("ok"));
                return;
            }
            if (session.mode() == FeishuDoctorSessionStore.Mode.REPLY_PATIENT) {
                long patientId = session.patientId();
                String reply = stripReplyPrefix(text);
                boolean sent = patientNotifyService.notifyPatient(patientId, "[医生回复] " + reply);
                jdbcTemplate.update(
                        "INSERT INTO encounter_log(patient_id, direction, content) VALUES (?, 'OUT', ?)",
                        patientId, "[医生回复] " + reply
                );
                if (session.alertId() > 0) {
                    jdbcTemplate.update(
                            """
                                    UPDATE alert_event
                                    SET status = 'ACKED', doctor_action = 'REPLY_PATIENT',
                                        handled_at = NOW(), updated_at = NOW()
                                    WHERE id = ? AND patient_id = ?
                                    """,
                            session.alertId(), patientId
                    );
                }
                sessionStore.clear(chatId);
                feishuMessageService.sendText("chat_id", chatId,
                        sent
                                ? "已发送给患者#" + patientId + "（信息隔离：仅该患者可见）"
                                : "已记录医生回复（患者#" + patientId + "）；请转发随访页链接给患者查看");
                return;
            }
            if (session.mode() == FeishuDoctorSessionStore.Mode.IGNORE_NOTE
                    || session.mode() == FeishuDoctorSessionStore.Mode.BRIEF_IGNORE_NOTE) {
                String note = text.trim();
                String msg = alertActionService.completeIgnoreNote(
                        session.alertId(), session.patientId(), note, "feishu");
                sessionStore.clear(chatId);
                feishuMessageService.sendText("chat_id", chatId, msg);
                return;
            }
        }

        // 快捷指令：改计划#12 | ...
        if ("text".equals(messageType) && text != null) {
            String trimmed = text.trim();
            if (patientLedgerService.looksLikeDashboard(trimmed)) {
                feishuMessageService.sendText("chat_id", chatId, patientLedgerService.renderDashboard());
                try {
                    var sync = patientLedgerService.syncToBitable();
                    if (Boolean.TRUE.equals(sync.get("ok"))) {
                        feishuMessageService.sendText("chat_id", chatId,
                                "多维表台账：" + sync.get("message"));
                    }
                } catch (Exception e) {
                    log.warn("看板同步多维表失败: {}", e.getMessage());
                }
                return;
            }
            if (trimmed.matches("(?s)^改计划\\s*#?\\d+[\\s\\S]*")) {
                long planId = Long.parseLong(trimmed.replaceAll("(?s)^改计划\\s*#?(\\d+).*", "$1"));
                String body = trimmed.replaceFirst("^改计划\\s*#?\\d+\\s*", "").trim();
                if (body.isBlank()) {
                    feishuMessageService.sendText("chat_id", chatId,
                            carePlanEditService.editGuide(planId, "该患者"));
                    Long pid = jdbcTemplate.query(
                            "SELECT patient_id FROM care_plan WHERE id = ?",
                            rs -> rs.next() ? rs.getLong(1) : null, planId);
                    if (pid != null) {
                        sessionStore.startEditPlan(chatId, planId, pid);
                    }
                } else {
                    var result = carePlanEditService.applyDoctorText(planId, body, chatId);
                    feishuMessageService.sendText("chat_id", chatId, String.valueOf(result.get("message")));
                }
                return;
            }
            if (trimmed.matches("(?s)^回复患者\\s*#?\\d+[\\s\\S]+")) {
                long patientId = Long.parseLong(trimmed.replaceAll("(?s)^回复患者\\s*#?(\\d+).*", "$1"));
                String body = trimmed.replaceFirst("^回复患者\\s*#?\\d+\\s*[：:]?\\s*", "").trim();
                boolean sent = patientNotifyService.notifyPatient(patientId, "[医生回复] " + body);
                jdbcTemplate.update(
                        "INSERT INTO encounter_log(patient_id, direction, content) VALUES (?, 'OUT', ?)",
                        patientId, "[医生回复] " + body
                );
                feishuMessageService.sendText("chat_id", chatId,
                        sent ? "已回复患者#" + patientId : "已记录给患者#" + patientId);
                return;
            }
            // 演示：查某某最近恢复情况 → 汇总；数据不足则问患者
            if (doctorQueryService.tryHandle(trimmed, chatId)) {
                return;
            }
        }

        caseIntakeService.handleImMessageEvent(event);
    }

    private String stripReplyPrefix(String text) {
        return text.replaceFirst("^(?i)(回复|答|告诉患者)\\s*[：:]\\s*", "").trim();
    }

    private String extractText(String messageType, String contentJson) {
        try {
            JsonNode c = objectMapper.readTree(contentJson == null ? "{}" : contentJson);
            if ("text".equals(messageType)) {
                return c.path("text").asText("");
            }
            if ("file".equals(messageType)) {
                return "文件：" + c.path("file_name").asText("");
            }
            return c.path("text").asText("");
        } catch (Exception e) {
            return contentJson;
        }
    }
}
