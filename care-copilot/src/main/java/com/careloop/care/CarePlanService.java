package com.careloop.care;

import com.careloop.collect.FollowupFormService;
import com.careloop.feishu.FeishuMessageService;
import com.careloop.ortho.OrthoKnowledgeService;
import com.careloop.patient.PatientNotifyService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CarePlanService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbcTemplate;
    private final FeishuMessageService feishuMessageService;
    private final PatientNotifyService patientNotifyService;
    private final OrthoKnowledgeService knowledgeService;
    private final FollowupFormService followupFormService;
    private final ObjectMapper objectMapper;

    @Value("${careloop.public-base-url:https://fromfreedom.top}")
    private String publicBaseUrl;

    @Value("${careloop.demo.feishu-receive-id-type:chat_id}")
    private String defaultReceiveIdType;

    @Value("${careloop.demo.feishu-receive-id:}")
    private String defaultReceiveId;

    public CarePlanService(JdbcTemplate jdbcTemplate,
                           FeishuMessageService feishuMessageService,
                           PatientNotifyService patientNotifyService,
                           OrthoKnowledgeService knowledgeService,
                           FollowupFormService followupFormService,
                           ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.feishuMessageService = feishuMessageService;
        this.patientNotifyService = patientNotifyService;
        this.knowledgeService = knowledgeService;
        this.followupFormService = followupFormService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Map<String, Object> createDraftAndNotifyDoctor(long patientId, String receiveIdType, String receiveId) {
        Map<String, Object> patient = loadPatient(patientId);
        String diagnosis = String.valueOf(patient.get("diagnosis"));
        String name = String.valueOf(patient.get("name"));
        String caseNotes = patient.get("case_notes") == null ? "" : String.valueOf(patient.get("case_notes"));
        String title = knowledgeService.specialty() + "照护计划 - " + name;

        ObjectNode content = objectMapper.createObjectNode();
        knowledgeService.enrichPlanMeta(
                content,
                diagnosis,
                String.valueOf(patient.get("doctor_name")),
                caseNotes
        );
        int followupDays = content.path("followupDays").asInt(14);
        ArrayNode nodes = content.putArray("nodes");
        knowledgeService.fillPlanNodes(nodes, name, diagnosis, followupDays);

        jdbcTemplate.update(
                """
                        INSERT INTO care_plan(patient_id, title, content_json, status)
                        VALUES (?, ?, CAST(? AS JSON), 'DRAFT')
                        """,
                patientId, title, content.toString()
        );
        Long planId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        String messageId = feishuMessageService.sendCarePlanConfirmCard(
                receiveIdType, receiveId,
                name,
                diagnosis,
                planId,
                content,
                editUrl(planId)
        );

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planId", planId);
        result.put("status", "DRAFT");
        result.put("specialty", knowledgeService.specialty());
        result.put("knowledgeBase", knowledgeService.summary());
        result.put("messageId", messageId);
        result.put("editUrl", editUrl(planId));
        result.put("tip", "已结合本病历生成随访计划，请医生确认或先编辑");
        return result;
    }

    public Map<String, Object> getPlan(long planId) {
        Map<String, Object> plan = loadPlan(planId);
        if (plan == null) {
            throw new IllegalArgumentException("计划不存在: " + planId);
        }
        return plan;
    }

    @Transactional
    public Map<String, Object> updateDraftNodes(long planId, List<Map<String, Object>> nodes,
                                                String careTips, boolean resendCard,
                                                String receiveIdType, String receiveId) {
        Map<String, Object> plan = loadPlan(planId);
        if (plan == null) {
            throw new IllegalArgumentException("计划不存在");
        }
        if (!"DRAFT".equals(plan.get("status"))) {
            throw new IllegalStateException("仅草稿计划可编辑，当前状态=" + plan.get("status"));
        }

        ObjectNode content;
        try {
            content = (ObjectNode) objectMapper.readTree(String.valueOf(plan.get("contentJson")));
        } catch (Exception e) {
            content = objectMapper.createObjectNode();
        }
        if (careTips != null && !careTips.isBlank()) {
            content.put("careTips", careTips);
        }
        ArrayNode arr = content.putArray("nodes");
        for (Map<String, Object> n : nodes) {
            ObjectNode node = arr.addObject();
            node.put("day", toInt(n.get("day")));
            node.put("question", String.valueOf(n.get("question")));
            if (n.get("tip") != null) {
                node.put("tip", String.valueOf(n.get("tip")));
            }
        }

        jdbcTemplate.update(
                "UPDATE care_plan SET content_json = CAST(? AS JSON), updated_at = NOW() WHERE id = ?",
                content.toString(), planId
        );

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("planId", planId);
        result.put("status", "DRAFT");
        result.put("content", content);
        result.put("updated", true);

        if (resendCard) {
            long patientId = ((Number) plan.get("patientId")).longValue();
            Map<String, Object> patient = loadPatient(patientId);
            String type = blank(receiveIdType) ? defaultReceiveIdType : receiveIdType;
            String id = blank(receiveId) ? defaultReceiveId : receiveId;
            String msgId = feishuMessageService.sendCarePlanConfirmCard(
                    type, id,
                    String.valueOf(patient.get("name")),
                    String.valueOf(patient.get("diagnosis")),
                    planId,
                    content,
                    editUrl(planId)
            );
            result.put("messageId", msgId);
            result.put("resent", true);
        }
        return result;
    }

    @Transactional
    public Map<String, Object> confirmPlan(long planId, String operatorOpenId) {
        Map<String, Object> plan = loadPlan(planId);
        if (plan == null) {
            return Map.of("ok", false, "message", "计划不存在");
        }
        if (!"DRAFT".equals(plan.get("status")) && !"ACTIVE".equals(plan.get("status"))) {
            return Map.of("ok", false, "message", "计划状态不可确认：" + plan.get("status"));
        }

        long patientId = ((Number) plan.get("patientId")).longValue();
        LocalDateTime base = LocalDateTime.now();
        jdbcTemplate.update(
                """
                        UPDATE care_plan
                        SET status = 'ACTIVE', confirmed_by = ?, confirmed_at = NOW(), updated_at = NOW()
                        WHERE id = ?
                        """,
                operatorOpenId, planId
        );
        jdbcTemplate.update(
                "UPDATE patient SET discharge_at = COALESCE(discharge_at, NOW()), updated_at = NOW() WHERE id = ?",
                patientId
        );

        jdbcTemplate.update("DELETE FROM followup_task WHERE plan_id = ?", planId);

        int followupCount = 0;
        try {
            ObjectNode content = (ObjectNode) objectMapper.readTree(String.valueOf(plan.get("contentJson")));
            int followupDays = content.path("followupDays").asInt(14);
            if (followupDays <= 0) {
                followupDays = 14;
            }
            boolean collectOn = !content.has("dailyCollectEnabled") || content.path("dailyCollectEnabled").asBoolean(true);
            int collectHour = content.path("dailyCollectHour").asInt(20);
            if (collectHour < 0 || collectHour > 23) {
                collectHour = 20;
            }
            int collectMinute = content.path("dailyCollectMinute").asInt(0);
            if (collectMinute < 0 || collectMinute > 59) {
                collectMinute = 0;
            }
            String diagnosis = content.path("diagnosis").asText("");
            JsonNode disease = knowledgeService.resolveDisease(diagnosis);
            if (content.hasNonNull("diseaseId") && !content.path("diseaseId").asText("").isBlank()) {
                JsonNode byId = knowledgeService.disease(content.path("diseaseId").asText());
                if (byId != null) {
                    disease = byId;
                }
            }
            List<String> extras = new ArrayList<>();
            if (content.path("doctorExtras").isArray()) {
                for (JsonNode e : content.path("doctorExtras")) {
                    String q = e.isTextual() ? e.asText() : e.path("question").asText(e.path("text").asText(""));
                    if (q != null && !q.isBlank()) {
                        extras.add(q.trim());
                    }
                }
            }
            LocalDateTime now = LocalDateTime.now();
            if (collectOn) {
                // 统一术后随访：每天最多 1 条；前7天每日，之后隔日并强制含病种关键日
                for (Integer day : knowledgeService.buildScheduleDays(followupDays, disease)) {
                    String dayTitle = "";
                    for (var node : content.path("nodes")) {
                        if (node.path("day").asInt() == day) {
                            dayTitle = node.path("title").asText("");
                            break;
                        }
                    }
                    ObjectNode form = knowledgeService.buildFormForDay(disease, day, dayTitle, extras, null);
                    String question = followupFormService.summarizeQuestion(
                            form.path("title").asText("术后随访 · D" + day), form);
                    LocalDateTime scheduled = base.toLocalDate().plusDays(day - 1L)
                            .atTime(collectHour, collectMinute, 0);
                    if (scheduled.isBefore(now)) {
                        scheduled = day == 1 ? now.plusMinutes(2)
                                : base.toLocalDate().plusDays(day - 1L).atTime(collectHour, collectMinute, 0);
                    }
                    insertTask(planId, patientId, day, "FOLLOWUP", question,
                            followupFormService.toJson(form), scheduled);
                    followupCount++;
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("解析随访计划失败", e);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("patientId", patientId);
        result.put("planId", planId);
        result.put("followupTasks", followupCount);
        result.put("nodeTasks", followupCount);
        result.put("dailyTasks", 0);
        result.put("message", "已确认出院计划（统一术后随访 " + followupCount
                + " 次），正在生成患者专属病患码");
        return result;
    }

    public List<Map<String, Object>> listPendingTasks(long patientId) {
        List<Map<String, Object>> rows;
        try {
            rows = jdbcTemplate.query(
                    """
                            SELECT id, plan_id, day_offset, question_text, scheduled_at, status,
                                   COALESCE(task_kind, 'FOLLOWUP') AS task_kind,
                                   question_form_json
                            FROM followup_task
                            WHERE patient_id = ? AND status IN ('PENDING', 'SENT')
                              AND (status = 'SENT' OR scheduled_at <= NOW())
                            ORDER BY scheduled_at ASC, id ASC
                            """,
                    (rs, i) -> mapTaskRow(rs, true),
                    patientId
            );
        } catch (Exception e) {
            rows = jdbcTemplate.query(
                    """
                            SELECT id, plan_id, day_offset, question_text, scheduled_at, status,
                                   COALESCE(task_kind, 'FOLLOWUP') AS task_kind
                            FROM followup_task
                            WHERE patient_id = ? AND status IN ('PENDING', 'SENT')
                              AND (status = 'SENT' OR scheduled_at <= NOW())
                            ORDER BY scheduled_at ASC, id ASC
                            """,
                    (rs, i) -> mapTaskRow(rs, false),
                    patientId
            );
        }
        boolean shortMode = knowledgeService.preferShortForm(countTrailingGreenAnswers(patientId));
        if (!shortMode) {
            return rows;
        }
        String diagnosis = loadPatientDiagnosis(patientId);
        JsonNode disease = knowledgeService.resolveDisease(diagnosis);
        for (Map<String, Object> row : rows) {
            int day = ((Number) row.get("dayOffset")).intValue();
            ObjectNode form = knowledgeService.buildFormForDay(disease, day, "", List.of(), null, true);
            row.put("form", followupFormService.toMap(form));
            row.put("shortMode", true);
        }
        return rows;
    }

    private String loadPatientDiagnosis(long patientId) {
        try {
            return jdbcTemplate.query(
                    "SELECT diagnosis FROM patient WHERE id = ?",
                    rs -> rs.next() ? rs.getString(1) : "",
                    patientId
            );
        } catch (Exception e) {
            return "";
        }
    }

    private int countTrailingGreenAnswers(long patientId) {
        List<String> answers = jdbcTemplate.query(
                """
                        SELECT answer_text FROM followup_task
                        WHERE patient_id = ? AND status = 'ANSWERED'
                          AND answer_text IS NOT NULL AND answer_text <> ''
                          AND answer_text NOT LIKE '【今日跳过】%'
                        ORDER BY answered_at DESC, id DESC
                        LIMIT 5
                        """,
                (rs, i) -> rs.getString(1),
                patientId
        );
        int n = 0;
        for (String a : answers) {
            var hit = knowledgeService.triage(a);
            String sig = followupFormService.highestSignalFromAnswer(a, null);
            if (!"GREEN".equalsIgnoreCase(hit.level()) || !"GREEN".equalsIgnoreCase(sig)) {
                break;
            }
            n++;
        }
        return n;
    }

    private Map<String, Object> mapTaskRow(java.sql.ResultSet rs, boolean withFormCol) throws java.sql.SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("taskId", rs.getLong("id"));
        row.put("planId", rs.getLong("plan_id"));
        row.put("dayOffset", rs.getInt("day_offset"));
        row.put("question", rs.getString("question_text"));
        row.put("scheduledAt", rs.getString("scheduled_at"));
        row.put("status", rs.getString("status"));
        String kind = rs.getString("task_kind");
        if (kind == null || kind.isBlank() || "DAILY".equalsIgnoreCase(kind) || "NODE".equalsIgnoreCase(kind)) {
            kind = "FOLLOWUP";
        }
        row.put("taskKind", kind);
        String formJson = null;
        if (withFormCol) {
            formJson = rs.getString("question_form_json");
        }
        JsonNode form = followupFormService.parseFormJson(formJson);
        if (form == null || !form.has("items")) {
            form = knowledgeService.buildFormForDay(
                    knowledgeService.resolveDisease(null),
                    rs.getInt("day_offset"),
                    "",
                    List.of(),
                    null
            );
        }
        row.put("form", followupFormService.toMap(form));
        row.put("answerMode", "choice_plus_custom");
        return row;
    }

    private void insertTask(long planId, long patientId, int day, String kind,
                            String question, String formJson, LocalDateTime scheduled) {
        try {
            jdbcTemplate.update(
                    """
                            INSERT INTO followup_task(plan_id, patient_id, day_offset, task_kind,
                              question_text, question_form_json, scheduled_at, status)
                            VALUES (?, ?, ?, ?, ?, CAST(? AS JSON), ?, 'PENDING')
                            """,
                    planId, patientId, day, kind, question, formJson, scheduled.format(FMT)
            );
        } catch (Exception e) {
            jdbcTemplate.update(
                    """
                            INSERT INTO followup_task(plan_id, patient_id, day_offset, task_kind,
                              question_text, scheduled_at, status)
                            VALUES (?, ?, ?, ?, ?, ?, 'PENDING')
                            """,
                    planId, patientId, day, kind, question, scheduled.format(FMT)
            );
        }
    }

    @Transactional
    public Map<String, Object> pushNextQuestion(long patientId) {
        List<Map<String, Object>> tasks = jdbcTemplate.query(
                """
                        SELECT id, question_text
                        FROM followup_task
                        WHERE patient_id = ? AND status = 'PENDING' AND scheduled_at <= NOW()
                        ORDER BY scheduled_at ASC, id ASC
                        LIMIT 1
                        """,
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("taskId", rs.getLong("id"));
                    row.put("question", rs.getString("question_text"));
                    return row;
                },
                patientId
        );
        if (tasks.isEmpty()) {
            return Map.of("pushed", false, "tip", "暂无到期随访任务");
        }
        return pushTask(patientId, tasks.get(0));
    }

    /**
     * 患者扫码绑定后：按「绑定日」重排未发送的每日/节点任务；
     * 当天规定时刻未到则排到该时刻，已过则尽快推送。
     * 返回应写入随访页欢迎语的首条随访文案。
     */
    @Transactional
    public String onPatientBound(long patientId) {
        Map<String, Object> plan = jdbcTemplate.query(
                """
                        SELECT id, content_json FROM care_plan
                        WHERE patient_id = ? AND status = 'ACTIVE'
                        ORDER BY id DESC LIMIT 1
                        """,
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("contentJson", rs.getString("content_json"));
                    return m;
                },
                patientId
        );
        if (plan == null) {
            return null;
        }
        int dailyHour = 20;
        int dailyMinute = 0;
        try {
            JsonNode content = objectMapper.readTree(String.valueOf(plan.get("contentJson")));
            dailyHour = content.path("dailyCollectHour").asInt(20);
            dailyMinute = content.path("dailyCollectMinute").asInt(0);
        } catch (Exception ignored) {
            // keep defaults
        }
        if (dailyHour < 0 || dailyHour > 23) {
            dailyHour = 20;
        }
        if (dailyMinute < 0 || dailyMinute > 59) {
            dailyMinute = 0;
        }

        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> pending = jdbcTemplate.query(
                """
                        SELECT id, day_offset, task_kind, question_text
                        FROM followup_task
                        WHERE patient_id = ? AND status = 'PENDING'
                        ORDER BY day_offset ASC, id ASC
                        """,
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("dayOffset", rs.getInt("day_offset"));
                    row.put("taskKind", rs.getString("task_kind"));
                    row.put("question", rs.getString("question_text"));
                    return row;
                },
                patientId
        );

        StringBuilder welcomeExtra = new StringBuilder();
        boolean welcomedToday = false;
        for (Map<String, Object> t : pending) {
            int day = ((Number) t.get("dayOffset")).intValue();
            long id = ((Number) t.get("id")).longValue();
            LocalDateTime scheduled = now.toLocalDate().plusDays(day - 1L).atTime(dailyHour, dailyMinute, 0);
            if (day == 1 && scheduled.isBefore(now)) {
                scheduled = now.plusMinutes(2);
            }
            jdbcTemplate.update(
                    "UPDATE followup_task SET scheduled_at = ?, task_kind = 'FOLLOWUP' WHERE id = ?",
                    scheduled.format(FMT), id
            );
            // 仅把「今天这一条」点亮，避免过期任务一次性砸下
            if (!welcomedToday && day == 1 && !scheduled.isAfter(now.plusMinutes(3))) {
                welcomeExtra.append("\n\n【今日随访】").append(t.get("question"));
                jdbcTemplate.update(
                        "UPDATE followup_task SET status = 'SENT', sent_at = NOW() WHERE id = ? AND status = 'PENDING'",
                        id
                );
                welcomedToday = true;
            }
        }
        return welcomeExtra.isEmpty() ? null : welcomeExtra.toString();
    }

    /** 调度器用：推送所有到期任务（每位患者当前只推一条，避免刷屏） */
    @Transactional
    public List<Map<String, Object>> pushAllDueTasks() {
        List<Long> patientIds = jdbcTemplate.query(
                """
                        SELECT DISTINCT patient_id
                        FROM followup_task
                        WHERE status = 'PENDING' AND scheduled_at <= NOW()
                        ORDER BY patient_id
                        """,
                (rs, i) -> rs.getLong(1)
        );
        List<Map<String, Object>> results = new ArrayList<>();
        for (Long pid : patientIds) {
            results.add(pushNextQuestion(pid));
        }
        return results;
    }

    private Map<String, Object> pushTask(long patientId, Map<String, Object> task) {
        long taskId = ((Number) task.get("taskId")).longValue();
        String question = String.valueOf(task.get("question"));

        jdbcTemplate.update(
                "UPDATE followup_task SET status = 'SENT', sent_at = NOW() WHERE id = ?",
                taskId
        );

        String kindLabel = "术后随访";
        boolean notified = patientNotifyService.notifyPatient(patientId, "[随访助手·" + kindLabel + "] " + question);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pushed", true);
        result.put("taskId", taskId);
        result.put("question", question);
        result.put("webNotified", notified);
        result.put("tip", notified ? "已飞书提醒并附随访页链接" : "已记入日志；请重新出码后再推送");
        return result;
    }

    private Map<String, Object> loadPlan(long planId) {
        return jdbcTemplate.query(
                "SELECT id, patient_id, title, status, content_json FROM care_plan WHERE id = ?",
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("planId", rs.getLong("id"));
                    row.put("patientId", rs.getLong("patient_id"));
                    row.put("title", rs.getString("title"));
                    row.put("status", rs.getString("status"));
                    row.put("contentJson", rs.getString("content_json"));
                    try {
                        JsonNode content = objectMapper.readTree(rs.getString("content_json"));
                        row.put("content", content);
                    } catch (Exception ignored) {
                        // ignore
                    }
                    return row;
                },
                planId
        );
    }

    private Map<String, Object> loadPatient(long patientId) {
        Map<String, Object> patient = jdbcTemplate.query(
                "SELECT id, name, diagnosis, doctor_name, case_notes FROM patient WHERE id = ?",
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("name", rs.getString("name"));
                    row.put("diagnosis", rs.getString("diagnosis"));
                    row.put("doctor_name", rs.getString("doctor_name"));
                    row.put("case_notes", rs.getString("case_notes"));
                    return row;
                },
                patientId
        );
        if (patient == null) {
            throw new IllegalArgumentException("患者不存在: " + patientId);
        }
        return patient;
    }

    private String editUrl(long planId) {
        String base = publicBaseUrl.endsWith("/")
                ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1)
                : publicBaseUrl;
        return base + "/nurse?planId=" + planId;
    }

    private boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private int toInt(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        return Integer.parseInt(String.valueOf(o));
    }
}
