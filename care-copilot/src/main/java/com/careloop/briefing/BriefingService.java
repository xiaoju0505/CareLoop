package com.careloop.briefing;

import com.careloop.collect.FollowupFormService;
import com.careloop.feishu.FeishuMessageService;
import com.careloop.ortho.OrthoKnowledgeService;
import com.careloop.trend.PatientTrendService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 诊前 30 秒简报：结论优先，流水进折叠区。
 */
@Service
public class BriefingService {

    private final JdbcTemplate jdbcTemplate;
    private final FeishuMessageService feishuMessageService;
    private final OrthoKnowledgeService knowledgeService;
    private final PatientTrendService patientTrendService;
    private final FollowupFormService followupFormService;

    public BriefingService(JdbcTemplate jdbcTemplate,
                           FeishuMessageService feishuMessageService,
                           OrthoKnowledgeService knowledgeService,
                           PatientTrendService patientTrendService,
                           FollowupFormService followupFormService) {
        this.jdbcTemplate = jdbcTemplate;
        this.feishuMessageService = feishuMessageService;
        this.knowledgeService = knowledgeService;
        this.patientTrendService = patientTrendService;
        this.followupFormService = followupFormService;
    }

    @Transactional
    public Map<String, Object> generateAndSend(long patientId, String receiveIdType, String receiveId) {
        Map<String, Object> patient = jdbcTemplate.query(
                "SELECT id, name, diagnosis, doctor_name, discharge_at FROM patient WHERE id = ?",
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("name", rs.getString("name"));
                    row.put("diagnosis", rs.getString("diagnosis"));
                    row.put("doctorName", rs.getString("doctor_name"));
                    row.put("dischargeAt", rs.getString("discharge_at"));
                    return row;
                },
                patientId
        );
        if (patient == null) {
            throw new IllegalArgumentException("患者不存在");
        }

        JsonNode disease = knowledgeService.resolveDisease(String.valueOf(patient.get("diagnosis")));
        String diseaseTitle = disease == null ? "骨科术后" : disease.path("title").asText("骨科术后");

        List<Map<String, Object>> openAlerts = jdbcTemplate.query(
                """
                        SELECT level, reason, status, doctor_action, created_at
                        FROM alert_event
                        WHERE patient_id = ?
                          AND (status IS NULL OR status IN ('OPEN','PENDING','NEW','ESCALATED')
                               OR doctor_action IS NULL OR doctor_action = '')
                        ORDER BY FIELD(level,'RED','YELLOW','GREEN'), id DESC
                        LIMIT 8
                        """,
                (rs, i) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("level", rs.getString("level"));
                    row.put("reason", rs.getString("reason"));
                    row.put("status", rs.getString("status"));
                    row.put("doctorAction", rs.getString("doctor_action"));
                    row.put("createdAt", rs.getString("created_at"));
                    return row;
                },
                patientId
        );

        List<Map<String, Object>> allAlerts = jdbcTemplate.query(
                """
                        SELECT level FROM alert_event WHERE patient_id = ?
                        ORDER BY id DESC LIMIT 30
                        """,
                (rs, i) -> Map.of("level", rs.getString("level")),
                patientId
        );

        String currentRisk = "GREEN";
        int openRed = 0;
        int openYellow = 0;
        for (Map<String, Object> a : openAlerts) {
            String lv = String.valueOf(a.get("level"));
            if ("RED".equals(lv)) {
                currentRisk = "RED";
                openRed++;
            } else if ("YELLOW".equals(lv)) {
                if (!"RED".equals(currentRisk)) {
                    currentRisk = "YELLOW";
                }
                openYellow++;
            }
        }
        String historicalPeak = "GREEN";
        for (Map<String, Object> a : allAlerts) {
            String lv = String.valueOf(a.get("level"));
            if ("RED".equals(lv)) {
                historicalPeak = "RED";
                break;
            }
            if ("YELLOW".equals(lv)) {
                historicalPeak = "YELLOW";
            }
        }

        Map<String, Object> taskStats = jdbcTemplate.query(
                """
                        SELECT
                          SUM(CASE WHEN status='ANSWERED' THEN 1 ELSE 0 END) answered,
                          COUNT(*) total
                        FROM followup_task WHERE patient_id = ?
                        """,
                rs -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    if (rs.next()) {
                        m.put("answered", rs.getInt("answered"));
                        m.put("total", rs.getInt("total"));
                    }
                    return m;
                },
                patientId
        );

        List<String> recentAnswers = jdbcTemplate.query(
                """
                        SELECT answer_text FROM followup_task
                        WHERE patient_id = ? AND answer_text IS NOT NULL AND answer_text <> ''
                        ORDER BY answered_at DESC LIMIT 8
                        """,
                (rs, i) -> rs.getString(1),
                patientId
        );

        boolean trendWorse = recentAnswers.stream().limit(3)
                .anyMatch(a -> a != null && (a.contains("变差") || a.contains("中度疼痛") || a.contains("剧痛")));
        boolean anticoagMiss = recentAnswers.stream()
                .anyMatch(a -> a != null && (a.contains("抗凝") && (a.contains("漏服") || a.contains("自行"))));
        boolean woundConcern = recentAnswers.stream()
                .anyMatch(a -> a != null && (a.contains("渗湿") || a.contains("红肿") || a.contains("裂开")));
        boolean swellingConcern = recentAnswers.stream()
                .anyMatch(a -> a != null && (a.contains("单侧") || a.contains("发紫")));

        String postopDay = estimatePostopDay(String.valueOf(patient.get("dischargeAt")));
        String patientMain = extractPatientNote(recentAnswers);

        List<String> threeAsks = buildThreeAsks(disease, openRed > 0, openYellow > 0,
                trendWorse, anticoagMiss, woundConcern, swellingConcern);

        String conclusions = buildConclusions(recentAnswers, diseaseTitle);

        StringBuilder md = new StringBuilder();
        md.append("**【诊前 30 秒】** ").append(patient.get("name"))
                .append(" · ").append(patient.get("diagnosis"))
                .append(" · ").append(diseaseTitle)
                .append(postopDay.isBlank() ? "" : " · 约术后" + postopDay)
                .append("\n");
        md.append("主治：").append(nullToDash(patient.get("doctorName"))).append("\n");
        md.append("**风险：** 当前").append(currentRisk)
                .append("（未关闭 红").append(openRed).append(" / 黄").append(openYellow).append("）")
                .append("｜历史峰值 ").append(historicalPeak)
                .append("｜依从 ").append(taskStats.getOrDefault("answered", 0))
                .append("/").append(taskStats.getOrDefault("total", 0)).append("\n\n");

        md.append("**一、结论**\n").append(conclusions).append("\n");
        if (patientMain != null && !patientMain.isBlank()) {
            md.append("· 患者主诉：").append(trim(patientMain, 80)).append("\n");
        }

        md.append("\n**二、必须处理（未关闭）**\n");
        if (openAlerts.isEmpty()) {
            md.append("· 无未关闭告警\n");
        } else {
            for (Map<String, Object> a : openAlerts) {
                md.append("· ").append(a.get("level")).append(" ")
                        .append(trim(String.valueOf(a.get("reason")), 100));
                if (a.get("doctorAction") != null && !String.valueOf(a.get("doctorAction")).isBlank()) {
                    md.append("（已注：").append(a.get("doctorAction")).append("）");
                }
                md.append("\n");
            }
        }

        md.append("\n**三、近7日趋势**\n");
        md.append(patientTrendService.renderTrendMarkdown(patientId, 7)
                .replace("【近7日趋势】\n", ""));

        md.append("\n**四、明天建议只问三句**\n");
        int i = 1;
        for (String q : threeAsks) {
            md.append(i++).append(". ").append(q).append("\n");
        }

        md.append("\n---\n**【展开】最近答卷摘录**\n");
        if (recentAnswers.isEmpty()) {
            md.append("· 暂无已答随访\n");
        } else {
            int c = 0;
            for (String a : recentAnswers) {
                if (c++ >= 5) {
                    break;
                }
                md.append("· ").append(trim(a, 100)).append("\n");
            }
        }

        String content = md.toString();
        String riskForCard = currentRisk;
        jdbcTemplate.update(
                """
                        INSERT INTO previsit_briefing(patient_id, visit_date, content_md, risk_level)
                        VALUES (?, CURDATE(), ?, ?)
                        """,
                patientId, content, riskForCard
        );
        Long briefingId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

        Long planId = jdbcTemplate.query(
                """
                        SELECT id FROM care_plan WHERE patient_id = ? AND status IN ('ACTIVE','DRAFT')
                        ORDER BY FIELD(status,'ACTIVE','DRAFT'), id DESC LIMIT 1
                        """,
                rs -> rs.next() ? rs.getLong(1) : 0L,
                patientId
        );

        String messageId = null;
        if (receiveId != null && !receiveId.isBlank()) {
            messageId = feishuMessageService.sendBriefingCard(
                    receiveIdType, receiveId,
                    String.valueOf(patient.get("name")),
                    riskForCard,
                    content,
                    briefingId,
                    patientId,
                    planId == null ? 0L : planId
            );
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("briefingId", briefingId);
        result.put("patientId", patientId);
        result.put("risk", riskForCard);
        result.put("currentRisk", currentRisk);
        result.put("historicalPeak", historicalPeak);
        result.put("content", content);
        result.put("messageId", messageId);
        result.put("threeAsks", threeAsks);
        return result;
    }

    private List<String> buildThreeAsks(JsonNode disease,
                                        boolean openRed, boolean openYellow,
                                        boolean trendWorse, boolean anticoagMiss,
                                        boolean woundConcern, boolean swellingConcern) {
        List<String> asks = new ArrayList<>();
        List<JsonNode> rules = new ArrayList<>();
        if (disease != null && disease.path("briefingPriorityRules").isArray()) {
            for (JsonNode r : disease.path("briefingPriorityRules")) {
                rules.add(r);
            }
        }
        JsonNode commonRules = knowledgeService.common().path("briefingPriorityRules");
        if (commonRules.isArray()) {
            for (JsonNode r : commonRules) {
                rules.add(r);
            }
        }
        for (JsonNode r : rules) {
            if (asks.size() >= 3) {
                break;
            }
            String when = r.path("when").asText("always");
            boolean hit = switch (when) {
                case "openRed" -> openRed;
                case "openYellow" -> openYellow;
                case "trendWorse" -> trendWorse;
                case "anticoagMiss" -> anticoagMiss;
                case "woundConcern" -> woundConcern;
                case "swellingConcern" -> swellingConcern;
                case "always" -> true;
                default -> false;
            };
            if (!hit) {
                continue;
            }
            String ask = r.path("ask").asText("");
            if (!ask.isBlank() && asks.stream().noneMatch(a -> a.equals(ask))) {
                asks.add(ask);
            }
        }
        while (asks.size() < 3) {
            asks.add("今天最希望医生重点检查或解答的问题是什么？");
            break;
        }
        return asks.size() > 3 ? asks.subList(0, 3) : asks;
    }

    private String buildConclusions(List<String> recentAnswers, String diseaseTitle) {
        if (recentAnswers == null || recentAnswers.isEmpty()) {
            return "· 院外答卷不足，建议护士今日电话补采后再诊\n";
        }
        String latest = recentAnswers.get(0);
        StringBuilder sb = new StringBuilder();
        sb.append("· 病种包：").append(diseaseTitle).append("\n");
        sb.append("· 最近一次随访信号：")
                .append(followupFormService.highestSignalFromAnswer(latest, null))
                .append("\n");
        if (latest.contains("伤口") || latest.contains("渗")) {
            sb.append("· 伤口线索：").append(snippet(latest, "伤口", 40)).append("\n");
        }
        if (latest.contains("疼痛") || latest.contains("痛")) {
            sb.append("· 疼痛线索：").append(snippet(latest, "疼痛", 40)).append("\n");
        }
        if (latest.contains("抗凝") || latest.contains("止痛")) {
            sb.append("· 用药线索：见最近答卷\n");
        }
        if (latest.contains("【今日跳过】")) {
            sb.append("· 注意：最近一次为跳过填写\n");
        }
        return sb.toString();
    }

    private String snippet(String text, String key, int max) {
        int i = text.indexOf(key);
        if (i < 0) {
            return trim(text, max);
        }
        return trim(text.substring(i), max);
    }

    private String extractPatientNote(List<String> answers) {
        for (String a : answers) {
            if (a == null) {
                continue;
            }
            int i = a.indexOf("今日最想告诉医生的一句话");
            if (i >= 0) {
                return a.substring(i);
            }
            i = a.indexOf("补充说明：");
            if (i >= 0) {
                return a.substring(i + 5);
            }
        }
        return "";
    }

    private String estimatePostopDay(String dischargeAt) {
        if (dischargeAt == null || dischargeAt.isBlank()) {
            return "";
        }
        try {
            java.time.LocalDate d = java.time.LocalDate.parse(dischargeAt.substring(0, 10));
            long days = java.time.temporal.ChronoUnit.DAYS.between(d, java.time.LocalDate.now());
            return "D" + Math.max(0, days);
        } catch (Exception e) {
            return "";
        }
    }

    private String nullToDash(Object o) {
        return o == null || String.valueOf(o).isBlank() ? "-" : String.valueOf(o);
    }

    private String trim(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ');
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
