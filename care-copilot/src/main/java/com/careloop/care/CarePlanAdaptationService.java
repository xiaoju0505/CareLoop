package com.careloop.care;

import com.careloop.feishu.FeishuMessageService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 连续异常 / 漏答 → 生成计划自适应建议，飞书一键确认。
 */
@Service
public class CarePlanAdaptationService {

    private final JdbcTemplate jdbcTemplate;
    private final CarePlanEditService carePlanEditService;
    private final FeishuMessageService feishuMessageService;

    @Value("${careloop.demo.feishu-receive-id-type:chat_id}")
    private String defaultReceiveIdType;

    @Value("${careloop.demo.feishu-receive-id:}")
    private String defaultReceiveId;

    public CarePlanAdaptationService(JdbcTemplate jdbcTemplate,
                                     CarePlanEditService carePlanEditService,
                                     FeishuMessageService feishuMessageService) {
        this.jdbcTemplate = jdbcTemplate;
        this.carePlanEditService = carePlanEditService;
        this.feishuMessageService = feishuMessageService;
    }

    public Map<String, Object> evaluateAndMaybeSuggest(long patientId) {
        Map<String, Object> out = new LinkedHashMap<>();
        Long planId = jdbcTemplate.query(
                """
                        SELECT id FROM care_plan
                        WHERE patient_id = ? AND status = 'ACTIVE'
                        ORDER BY id DESC LIMIT 1
                        """,
                rs -> rs.next() ? rs.getLong(1) : null,
                patientId
        );
        if (planId == null) {
            out.put("suggested", false);
            out.put("reason", "无 ACTIVE 计划");
            return out;
        }

        Integer alertCount = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*) FROM alert_event
                        WHERE patient_id = ? AND level IN ('YELLOW','RED')
                          AND created_at >= DATE_SUB(NOW(), INTERVAL 3 DAY)
                        """,
                Integer.class, patientId
        );
        Integer missedDaily = jdbcTemplate.queryForObject(
                """
                        SELECT COUNT(*) FROM followup_task
                        WHERE patient_id = ?
                          AND COALESCE(task_kind,'FOLLOWUP') IN ('FOLLOWUP','DAILY','NODE')
                          AND status IN ('PENDING','SENT')
                          AND scheduled_at < DATE_SUB(NOW(), INTERVAL 1 DAY)
                          AND scheduled_at >= DATE_SUB(NOW(), INTERVAL 7 DAY)
                        """,
                Integer.class, patientId
        );

        List<String> suggestions = new ArrayList<>();
        String applyNl = null;
        if (alertCount != null && alertCount >= 2) {
            suggestions.add("近3日出现 " + alertCount + " 次黄/红警，建议明日随访重点关注伤口与疼痛");
            applyNl = "加要求：请重点关注伤口渗液与疼痛是否加重";
        }
        if (missedDaily != null && missedDaily >= 2) {
            suggestions.add("近7日漏答随访 " + missedDaily + " 次，建议将采集时间改到上午10:00");
            applyNl = (applyNl == null ? "" : applyNl + "；") + "每日时间：10:00";
        }
        if (suggestions.isEmpty()) {
            out.put("suggested", false);
            return out;
        }

        String patientName = jdbcTemplate.query(
                "SELECT name FROM patient WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : "患者",
                patientId
        );

        StringBuilder body = new StringBuilder();
        body.append("**患者：** ").append(patientName).append("（#").append(patientId).append("）\n");
        body.append("**计划ID：** ").append(planId).append("\n");
        body.append("**自适应建议：**\n");
        for (String s : suggestions) {
            body.append("- ").append(s).append("\n");
        }
        body.append("\n点「确认采纳」将自动改写随访计划并重排待办。");

        out.put("suggested", true);
        out.put("planId", planId);
        out.put("patientId", patientId);
        out.put("applyNl", applyNl);
        out.put("suggestions", suggestions);

        if (defaultReceiveId != null && !defaultReceiveId.isBlank()) {
            ObjectNode card = feishuMessageService.buildAdaptPlanCard(
                    patientName, planId, patientId, body.toString(), applyNl);
            String msgId = feishuMessageService.sendInteractive(defaultReceiveIdType, defaultReceiveId, card);
            out.put("feishuMessageId", msgId);
        }
        jdbcTemplate.update(
                "INSERT INTO encounter_log(patient_id, direction, content) VALUES (?, 'OUT', ?)",
                patientId, "[计划自适应建议] " + String.join("；", suggestions)
        );
        return out;
    }

    public String confirmAdapt(long planId, String applyNl, String operatorOpenId) {
        if (applyNl == null || applyNl.isBlank()) {
            return "缺少可执行建议文本";
        }
        Map<String, Object> result = carePlanEditService.applyDoctorText(planId, applyNl, null);
        boolean ok = Boolean.TRUE.equals(result.get("ok"));
        Long patientId = jdbcTemplate.query(
                "SELECT patient_id FROM care_plan WHERE id = ?",
                rs -> rs.next() ? rs.getLong(1) : null,
                planId
        );
        if (patientId != null) {
            jdbcTemplate.update(
                    "INSERT INTO encounter_log(patient_id, direction, content) VALUES (?, 'OUT', ?)",
                    patientId,
                    "[医生确认自适应] operator=" + operatorOpenId + " → " + applyNl
            );
        }
        return ok
                ? "已采纳计划自适应：" + result.get("message")
                : "采纳失败：" + result.get("message");
    }
}
