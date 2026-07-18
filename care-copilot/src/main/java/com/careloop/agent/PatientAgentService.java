package com.careloop.agent;

import com.careloop.care.CarePlanAdaptationService;
import com.careloop.collect.StructuredCollectService;
import com.careloop.feishu.FeishuMessageService;
import com.careloop.ortho.OrthoKnowledgeService;
import com.careloop.patient.PatientContextService;
import com.careloop.triage.TriageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 患者侧：定位为「随访采集助手」，不是闲聊机器人。
 * 主要处理：到期随访/每日收集的回复；急重症信号升级医生。
 */
@Service
public class PatientAgentService {

    private final JdbcTemplate jdbcTemplate;
    private final TriageService triageService;
    private final OrthoKnowledgeService knowledgeService;
    private final FeishuMessageService feishuMessageService;
    private final PatientContextService patientContextService;
    private final StructuredCollectService structuredCollectService;
    private final CarePlanAdaptationService carePlanAdaptationService;
    private final ObjectMapper objectMapper;

    @Value("${careloop.demo.feishu-receive-id-type:chat_id}")
    private String defaultReceiveIdType;

    @Value("${careloop.demo.feishu-receive-id:}")
    private String defaultReceiveId;

    public PatientAgentService(JdbcTemplate jdbcTemplate,
                               TriageService triageService,
                               OrthoKnowledgeService knowledgeService,
                               FeishuMessageService feishuMessageService,
                               PatientContextService patientContextService,
                               StructuredCollectService structuredCollectService,
                               CarePlanAdaptationService carePlanAdaptationService,
                               ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.triageService = triageService;
        this.knowledgeService = knowledgeService;
        this.feishuMessageService = feishuMessageService;
        this.patientContextService = patientContextService;
        this.structuredCollectService = structuredCollectService;
        this.carePlanAdaptationService = carePlanAdaptationService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Map<String, Object> handle(long patientId, String text, Long taskId) {
        Optional<Map<String, Object>> ctxOpt = patientContextService.loadById(patientId);
        if (ctxOpt.isEmpty()) {
            return Map.of("ok", false, "assistantReply", "未找到您的随访档案，请联系医护重新扫码绑定。");
        }
        Map<String, Object> ctx = ctxOpt.get();
        String name = String.valueOf(ctx.getOrDefault("name", "患者"));

        if (text != null && text.startsWith("【今日跳过】")) {
            return handleSkip(patientId, name, text, taskId);
        }

        Map<String, Object> fields = structuredCollectService.extract(text);
        TriageService.TriageResult triage = triageService.triage(text, patientId);

        ObjectNode structured = objectMapper.createObjectNode();
        structured.put("level", triage.level());
        structured.put("reason", triage.reason());
        structured.put("category", triage.category());
        structured.put("ruleId", triage.ruleId() == null ? "" : triage.ruleId());
        structured.put("matchedKeyword", triage.matchedKeyword() == null ? "" : triage.matchedKeyword());
        structured.put("suggestedAction", triage.suggestedAction() == null ? "" : triage.suggestedAction());
        structured.put("evidenceSummary", triage.evidenceSummary() == null ? "" : triage.evidenceSummary());
        structured.put("patientId", patientId);
        structured.put("intent", "FOLLOWUP_COLLECT");
        if (taskId != null && taskId > 0) {
            structured.put("taskId", taskId);
        }
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            if (e.getValue() instanceof Number n) {
                structured.put(e.getKey(), n.doubleValue());
            } else if (e.getValue() instanceof Boolean b) {
                structured.put(e.getKey(), b);
            } else if (e.getValue() != null) {
                structured.put(e.getKey(), String.valueOf(e.getValue()));
            }
        }

        jdbcTemplate.update(
                """
                        INSERT INTO encounter_log(patient_id, direction, content, structured_json)
                        VALUES (?, 'IN', ?, CAST(? AS JSON))
                        """,
                patientId, text, structured.toString()
        );
        Long logId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("patientId", patientId);
        result.put("logId", logId);
        result.put("triage", triageService.toMap(triage));
        result.put("fields", fields);

        if ("RED".equals(triage.level()) || "YELLOW".equals(triage.level())) {
            if (taskId != null && taskId > 0) {
                jdbcTemplate.update(
                        """
                                UPDATE followup_task
                                SET status = 'ANSWERED', answered_at = NOW(), answer_text = ?
                                WHERE id = ? AND patient_id = ?
                                """,
                        text, taskId, patientId
                );
            }
            Map<String, Object> escalated = escalateClinical(result, patientId, name, triage, logId);
            try {
                carePlanAdaptationService.evaluateAndMaybeSuggest(patientId);
            } catch (Exception ignored) {
                // 自适应建议失败不影响主流程
            }
            return escalated;
        }

        if (taskId != null && taskId > 0) {
            jdbcTemplate.update(
                    """
                            UPDATE followup_task
                            SET status = 'ANSWERED', answered_at = NOW(), answer_text = ?
                            WHERE id = ? AND patient_id = ?
                            """,
                    text, taskId, patientId
            );
            String reply = "已收到您的随访回复，感谢配合。请继续按医嘱休养；下一轮随访提醒到达时再回复即可。"
                    + "若出现胸闷气促、高热、大量渗液或单侧小腿明显胀痛发紫，请立即就医。";
            saveOut(patientId, reply);
            result.put("assistantReply", reply);
            result.put("alertCreated", false);
            return result;
        }

        String reply = "我是术后随访助手，主要在医生设定的时间向您收集恢复情况（不是日常聊天机器人）。"
                + "请留意随访提醒后按提示回复；如有紧急不适请及时就医或联系医院。";
        saveOut(patientId, reply);
        result.put("assistantReply", reply);
        result.put("alertCreated", false);
        return result;
    }

    /** 跳过：记完成、不升诊、不告警；不计入绿日 streak（答卷前缀已排除） */
    private Map<String, Object> handleSkip(long patientId, String name, String text, Long taskId) {
        ObjectNode structured = objectMapper.createObjectNode();
        structured.put("level", "GREEN");
        structured.put("reason", "患者跳过本日随访");
        structured.put("category", "跳过");
        structured.put("ruleId", "skip-day");
        structured.put("patientId", patientId);
        structured.put("intent", "FOLLOWUP_SKIP");
        if (taskId != null && taskId > 0) {
            structured.put("taskId", taskId);
        }
        jdbcTemplate.update(
                """
                        INSERT INTO encounter_log(patient_id, direction, content, structured_json)
                        VALUES (?, 'IN', ?, CAST(? AS JSON))
                        """,
                patientId, text, structured.toString()
        );
        Long logId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        if (taskId != null && taskId > 0) {
            jdbcTemplate.update(
                    """
                            UPDATE followup_task
                            SET status = 'ANSWERED', answered_at = NOW(), answer_text = ?
                            WHERE id = ? AND patient_id = ?
                            """,
                    text, taskId, patientId
            );
        }
        String reply = "已记录今日跳过。若身体不适请随时就医；明日提醒到达时请尽量完成随访。";
        saveOut(patientId, reply);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("patientId", patientId);
        result.put("logId", logId);
        result.put("skipped", true);
        result.put("triage", Map.of(
                "level", "GREEN",
                "reason", "患者跳过本日随访",
                "category", "跳过",
                "ruleId", "skip-day",
                "matchedKeyword", "",
                "suggestedAction", "明日补答或护士电话关注依从",
                "evidenceSummary", text
        ));
        result.put("fields", Map.of());
        result.put("assistantReply", reply);
        result.put("alertCreated", false);
        return result;
    }

    private Map<String, Object> escalateClinical(Map<String, Object> result, long patientId, String name,
                                                 TriageService.TriageResult triage, Long logId) {
        jdbcTemplate.update(
                """
                        INSERT INTO alert_event(patient_id, level, reason, source_log_id, status)
                        VALUES (?, ?, ?, ?, 'OPEN')
                        """,
                patientId, triage.level(), triage.reason(), logId
        );
        Long alertId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        Long planId = jdbcTemplate.query(
                """
                        SELECT id FROM care_plan WHERE patient_id = ? AND status = 'ACTIVE'
                        ORDER BY id DESC LIMIT 1
                        """,
                rs -> rs.next() ? rs.getLong(1) : 0L,
                patientId
        );
        if (defaultReceiveId != null && !defaultReceiveId.isBlank()) {
            String msgId = feishuMessageService.sendAlertCard(
                    defaultReceiveIdType, defaultReceiveId,
                    name + "（#" + patientId + "）",
                    triage.level(),
                    triage.reason(),
                    alertId,
                    planId == null ? 0L : planId,
                    triage.ruleId(),
                    triage.matchedKeyword(),
                    triage.suggestedAction(),
                    triage.evidenceSummary()
            );
            jdbcTemplate.update("UPDATE alert_event SET feishu_msg_id = ? WHERE id = ?", msgId, alertId);
            result.put("feishuMessageId", msgId);
        }
        String reply = knowledgeService.replyForLevel(triage.level());
        saveOut(patientId, reply);
        result.put("assistantReply", reply);
        result.put("alertId", alertId);
        result.put("alertCreated", true);
        return result;
    }

    private void saveOut(long patientId, String reply) {
        jdbcTemplate.update(
                "INSERT INTO encounter_log(patient_id, direction, content) VALUES (?, 'OUT', ?)",
                patientId, "[随访助手] " + reply
        );
    }
}
