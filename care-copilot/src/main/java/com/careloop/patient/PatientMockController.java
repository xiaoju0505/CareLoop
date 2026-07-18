package com.careloop.patient;

import com.careloop.collect.FollowupFormService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 患者随访网页对话 API。
 */
@RestController
@RequestMapping("/api/patient/mock")
public class PatientMockController {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final PatientChatService patientChatService;
    private final com.careloop.care.CarePlanService carePlanService;
    private final DeviceMockService deviceMockService;
    private final FollowupFormService followupFormService;
    private final PatientContextService patientContextService;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public PatientMockController(PatientChatService patientChatService,
                                 com.careloop.care.CarePlanService carePlanService,
                                 DeviceMockService deviceMockService,
                                 FollowupFormService followupFormService,
                                 PatientContextService patientContextService,
                                 org.springframework.jdbc.core.JdbcTemplate jdbcTemplate,
                                 ObjectMapper objectMapper) {
        this.patientChatService = patientChatService;
        this.carePlanService = carePlanService;
        this.deviceMockService = deviceMockService;
        this.followupFormService = followupFormService;
        this.patientContextService = patientContextService;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 患者随访页发消息。支持纯文本，或选择题+自定义：
     * {
     *   "patientId": "1",
     *   "taskId": "1",
     *   "selections": [ { "id":"wound", "label":"伤口", "chosen":["干燥"], "custom":"..." } ],
     *   "note": "补充"
     * }
     */
    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody Map<String, Object> req) {
        long patientId = Long.parseLong(String.valueOf(req.getOrDefault("patientId", "1")));
        String text = req.get("text") == null ? "" : String.valueOf(req.get("text")).trim();
        boolean skipped = req.get("skipped") != null && Boolean.parseBoolean(String.valueOf(req.get("skipped")));
        Long taskId = null;
        if (req.get("taskId") != null && !String.valueOf(req.get("taskId")).isBlank()) {
            taskId = Long.parseLong(String.valueOf(req.get("taskId")));
        }
        if (!skipped && req.containsKey("selections") && taskId != null) {
            JsonNode form = loadTaskForm(patientId, taskId);
            if (form != null) {
                List<String> missing = followupFormService.missingRequiredLabels(form, req);
                if (!missing.isEmpty()) {
                    throw new IllegalArgumentException("请完成必填项：" + String.join("、", missing));
                }
            }
        }
        if (text.isBlank() && (skipped || req.containsKey("selections") || req.containsKey("note"))) {
            text = followupFormService.composeAnswer(req);
        }
        if (text.isBlank()) {
            throw new IllegalArgumentException("请至少选择一项或填写描述");
        }
        String receiveIdType = req.get("receiveIdType") == null ? null : String.valueOf(req.get("receiveIdType"));
        String receiveId = req.get("receiveId") == null ? null : String.valueOf(req.get("receiveId"));
        return new LinkedHashMap<>(patientChatService.handlePatientMessage(
                patientId, text, taskId, receiveIdType, receiveId
        ));
    }

    private JsonNode loadTaskForm(long patientId, long taskId) {
        try {
            List<Map<String, Object>> tasks = carePlanService.listPendingTasks(patientId);
            for (Map<String, Object> t : tasks) {
                Object id = t.get("taskId");
                if (id instanceof Number n && n.longValue() == taskId) {
                    Object form = t.get("form");
                    if (form == null) {
                        return null;
                    }
                    return objectMapper.valueToTree(form);
                }
            }
        } catch (Exception ignored) {
        }
        try {
            String json = jdbcTemplate.query(
                    "SELECT question_form_json FROM followup_task WHERE id = ? AND patient_id = ?",
                    rs -> rs.next() ? rs.getString(1) : null,
                    taskId, patientId
            );
            return followupFormService.parseFormJson(json);
        } catch (Exception e) {
            return null;
        }
    }

    @GetMapping("/tasks")
    public List<Map<String, Object>> tasks(@RequestParam(defaultValue = "1") long patientId) {
        return carePlanService.listPendingTasks(patientId);
    }

    /**
     * App 首页 / 我的：今日待办、进度、计划摘要、医生加项。
     */
    @GetMapping("/summary")
    public Map<String, Object> summary(@RequestParam(defaultValue = "1") long patientId) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> patient = patientContextService.loadById(patientId)
                .orElseThrow(() -> new IllegalArgumentException("患者不存在"));
        out.put("patient", patient);

        List<Map<String, Object>> pending = carePlanService.listPendingTasks(patientId);
        out.put("pendingTasks", pending);
        out.put("pendingCount", pending.size());

        String today = LocalDate.now().format(DAY);
        int doneToday = countInt(
                """
                        SELECT COUNT(*) FROM followup_task
                        WHERE patient_id = ? AND status = 'ANSWERED'
                          AND DATE(COALESCE(answered_at, scheduled_at)) = ?
                        """,
                patientId, today
        );
        int answeredTotal = countInt(
                "SELECT COUNT(*) FROM followup_task WHERE patient_id = ? AND status = 'ANSWERED'",
                patientId
        );
        int totalTasks = countInt(
                "SELECT COUNT(*) FROM followup_task WHERE patient_id = ?",
                patientId
        );
        out.put("doneToday", doneToday);
        out.put("answeredTotal", answeredTotal);
        out.put("totalTasks", totalTasks);
        int todayTarget = Math.max(pending.size() + doneToday, 1);
        out.put("todayProgress", Math.min(100, (int) Math.round(100.0 * doneToday / todayTarget)));

        Map<String, Object> next = pending.isEmpty() ? null : pending.get(0);
        out.put("nextTask", next);

        Map<String, Object> plan = new LinkedHashMap<>();
        Long planId = patientContextService.activePlanId(patientId).orElse(null);
        if (planId != null) {
            jdbcTemplate.query(
                    "SELECT id, title, status, content_json FROM care_plan WHERE id = ?",
                    rs -> {
                        if (!rs.next()) {
                            return null;
                        }
                        plan.put("planId", rs.getLong("id"));
                        plan.put("title", rs.getString("title"));
                        plan.put("status", rs.getString("status"));
                        String json = rs.getString("content_json");
                        List<String> extras = new ArrayList<>();
                        int nodeCount = 0;
                        try {
                            JsonNode content = objectMapper.readTree(json == null ? "{}" : json);
                            if (content.path("doctorExtras").isArray()) {
                                for (JsonNode e : content.path("doctorExtras")) {
                                    String q = e.path("question").asText("");
                                    if (q.isBlank()) {
                                        q = e.path("text").asText("");
                                    }
                                    if (!q.isBlank()) {
                                        extras.add(q);
                                    }
                                }
                            }
                            if (content.path("nodes").isArray()) {
                                nodeCount = content.path("nodes").size();
                            } else if (content.path("followupNodes").isArray()) {
                                nodeCount = content.path("followupNodes").size();
                            }
                        } catch (Exception ignored) {
                            // ignore
                        }
                        plan.put("doctorExtras", extras);
                        plan.put("nodeCount", nodeCount);
                        return null;
                    },
                    planId
            );
        }
        String dischargeAt = patient.get("dischargeAt") == null ? null : String.valueOf(patient.get("dischargeAt"));
        int postopDay = 0;
        if (dischargeAt != null && !dischargeAt.isBlank()) {
            try {
                LocalDate d = LocalDateTime.parse(dischargeAt.replace(' ', 'T').substring(0, Math.min(19, dischargeAt.length())))
                        .toLocalDate();
                postopDay = (int) Math.max(0, ChronoUnit.DAYS.between(d, LocalDate.now()));
            } catch (Exception e) {
                try {
                    LocalDate d = LocalDate.parse(dischargeAt.substring(0, 10));
                    postopDay = (int) Math.max(0, ChronoUnit.DAYS.between(d, LocalDate.now()));
                } catch (Exception ignored) {
                    // ignore
                }
            }
        }
        plan.put("dischargeAt", dischargeAt);
        plan.put("postopDay", postopDay);
        plan.put("nextCollectAt", next == null ? null : next.get("scheduledAt"));
        out.put("plan", plan);

        List<Map<String, Object>> tips = List.of(
                Map.of("title", "抬高患肢", "body", "休息时抬高患肢高于心脏，减轻肿胀。"),
                Map.of("title", "伤口护理", "body", "保持敷料清洁干燥，勿自行拆除。"),
                Map.of("title", "防跌倒", "body", "下地使用助行器，尽量有人陪同。")
        );
        out.put("careTips", tips);
        return out;
    }

    @GetMapping("/timeline")
    public List<Map<String, Object>> timeline(@RequestParam(defaultValue = "1") long patientId) {
        return jdbcTemplate.query(
                """
                        SELECT id, direction, content, structured_json, created_at
                        FROM encounter_log
                        WHERE patient_id = ?
                        ORDER BY id DESC
                        LIMIT 80
                        """,
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("direction", rs.getString("direction"));
                    m.put("content", rs.getString("content"));
                    m.put("structured", rs.getString("structured_json") == null ? "" : rs.getString("structured_json"));
                    m.put("createdAt", rs.getString("created_at"));
                    return m;
                },
                patientId
        );
    }

    private int countInt(String sql, Object... args) {
        Integer n = jdbcTemplate.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    /** 模拟蓝牙手环：生成动态数据并入库，供随访报告汇总 */
    @PostMapping("/device/sync")
    public Map<String, Object> deviceSync(@RequestBody Map<String, String> req) {
        long patientId = Long.parseLong(req.getOrDefault("patientId", "0"));
        if (patientId <= 0) {
            throw new IllegalArgumentException("patientId 无效");
        }
        return deviceMockService.snapshotAndSave(patientId);
    }

    @GetMapping("/device/latest")
    public Map<String, Object> deviceLatest(@RequestParam long patientId) {
        return deviceMockService.latest(patientId);
    }
}
