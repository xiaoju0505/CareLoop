package com.careloop.trend;

import com.careloop.collect.StructuredCollectService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 近 N 日疼痛 / 用药依从 / 手环趋势，供飞书报告与「查恢复」使用。
 */
@Service
public class PatientTrendService {

    private final JdbcTemplate jdbcTemplate;
    private final StructuredCollectService structuredCollectService;
    private final ObjectMapper objectMapper;

    public PatientTrendService(JdbcTemplate jdbcTemplate,
                               StructuredCollectService structuredCollectService,
                               ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.structuredCollectService = structuredCollectService;
        this.objectMapper = objectMapper;
    }

    public String renderTrendMarkdown(long patientId, int days) {
        Map<String, Object> t = build(patientId, days);
        StringBuilder sb = new StringBuilder();
        sb.append("【近").append(days).append("日趋势】\n");
        sb.append("- 疼痛评分序列：").append(t.get("painSeries")).append("\n");
        sb.append("- 较昨日疼痛：").append(t.get("painDeltaLabel")).append("\n");
        sb.append("- 用药依从：").append(t.get("medSummary")).append("\n");
        sb.append("- 手环静息心率：").append(t.get("hrSeries")).append("\n");
        sb.append("- 手环血压：").append(t.get("bpSeries")).append("\n");
        sb.append("- 较昨日心率：").append(t.get("hrDeltaLabel")).append("\n");
        return sb.toString();
    }

    public Map<String, Object> build(long patientId, int days) {
        List<Integer> pain = new ArrayList<>();
        int[] med = new int[3]; // ok / miss / partial

        List<Map<String, Object>> answers = jdbcTemplate.query(
                """
                        SELECT answer_text FROM followup_task
                        WHERE patient_id = ? AND answer_text IS NOT NULL AND answer_text <> ''
                          AND answered_at >= DATE_SUB(NOW(), INTERVAL ? DAY)
                        ORDER BY answered_at ASC
                        """,
                (rs, i) -> Map.of("text", rs.getString("answer_text")),
                patientId, days
        );
        for (Map<String, Object> a : answers) {
            applyFields(structuredCollectService.extract(String.valueOf(a.get("text"))), pain, med);
        }

        List<Map<String, Object>> inLogs = jdbcTemplate.query(
                """
                        SELECT content, structured_json FROM encounter_log
                        WHERE patient_id = ? AND direction = 'IN'
                          AND created_at >= DATE_SUB(NOW(), INTERVAL ? DAY)
                          AND content NOT LIKE '[手环模拟]%'
                        ORDER BY id ASC
                        LIMIT 40
                        """,
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("content", rs.getString("content"));
                    m.put("structured", rs.getString("structured_json"));
                    return m;
                },
                patientId, days
        );
        for (Map<String, Object> log : inLogs) {
            applyFields(fromStructuredOrText(log), pain, med);
        }

        List<Integer> hrs = new ArrayList<>();
        List<String> bps = new ArrayList<>();
        List<Map<String, Object>> devices = jdbcTemplate.query(
                """
                        SELECT structured_json FROM encounter_log
                        WHERE patient_id = ? AND content LIKE '[手环模拟]%'
                          AND created_at >= DATE_SUB(NOW(), INTERVAL ? DAY)
                        ORDER BY id ASC
                        LIMIT 20
                        """,
                (rs, i) -> Map.of("structured", rs.getString("structured_json") == null
                        ? "" : rs.getString("structured_json")),
                patientId, days
        );
        for (Map<String, Object> d : devices) {
            JsonNode n = parseJson(String.valueOf(d.get("structured")));
            if (n == null) {
                continue;
            }
            if (n.has("restingHeartRate")) {
                hrs.add(n.path("restingHeartRate").asInt());
            } else if (n.has("heartRate")) {
                hrs.add(n.path("heartRate").asInt());
            }
            if (n.hasNonNull("bloodPressure")) {
                bps.add(n.path("bloodPressure").asText());
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("painSeries", seriesOrDash(pain));
        out.put("painDeltaLabel", deltaLabel(pain, "分"));
        out.put("medSummary", medSummary(med));
        out.put("hrSeries", seriesOrDash(hrs));
        out.put("bpSeries", bps.isEmpty() ? "暂无" : String.join(" → ", trimList(bps, 5)));
        out.put("hrDeltaLabel", deltaLabel(hrs, "bpm"));
        out.put("painLatest", pain.isEmpty() ? null : pain.get(pain.size() - 1));
        out.put("hrLatest", hrs.isEmpty() ? null : hrs.get(hrs.size() - 1));
        return out;
    }

    private void applyFields(Map<String, Object> f, List<Integer> pain, int[] med) {
        Object p = f.get("painScore");
        if (p instanceof Number n) {
            pain.add(n.intValue());
        }
        Object v = f.get("medAdherence");
        if (v == null) {
            return;
        }
        switch (String.valueOf(v)) {
            case "OK" -> med[0]++;
            case "MISS" -> med[1]++;
            case "PARTIAL" -> med[2]++;
            default -> {
            }
        }
    }

    private Map<String, Object> fromStructuredOrText(Map<String, Object> log) {
        JsonNode n = parseJson(String.valueOf(log.get("structured")));
        if (n != null && n.has("painScore")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("painScore", n.path("painScore").asInt());
            if (n.has("medAdherence")) {
                m.put("medAdherence", n.path("medAdherence").asText());
            }
            return m;
        }
        if (n != null && n.has("fields")) {
            Map<String, Object> m = new LinkedHashMap<>();
            JsonNode fields = n.path("fields");
            if (fields.has("painScore")) {
                m.put("painScore", fields.path("painScore").asInt());
            }
            if (fields.has("medAdherence")) {
                m.put("medAdherence", fields.path("medAdherence").asText());
            }
            return m;
        }
        return structuredCollectService.extract(String.valueOf(log.get("content")));
    }

    private JsonNode parseJson(String s) {
        if (s == null || s.isBlank() || "null".equals(s)) {
            return null;
        }
        try {
            return objectMapper.readTree(s);
        } catch (Exception e) {
            return null;
        }
    }

    private String seriesOrDash(List<Integer> list) {
        if (list == null || list.isEmpty()) {
            return "暂无";
        }
        List<Integer> show = list.size() <= 7 ? list : list.subList(list.size() - 7, list.size());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < show.size(); i++) {
            if (i > 0) {
                sb.append(" → ");
            }
            sb.append(show.get(i));
        }
        return sb.toString();
    }

    private String deltaLabel(List<Integer> list, String unit) {
        if (list == null || list.size() < 2) {
            return "样本不足";
        }
        int last = list.get(list.size() - 1);
        int prev = list.get(list.size() - 2);
        int d = last - prev;
        if (d == 0) {
            return "持平（" + last + unit + "）";
        }
        return (d > 0 ? "↑ +" : "↓ ") + d + unit + "（" + prev + "→" + last + "）";
    }

    private String medSummary(int[] med) {
        int total = med[0] + med[1] + med[2];
        if (total == 0) {
            return "暂无结构化用药记录";
        }
        return "按时" + med[0] + " / 漏服" + med[1] + " / 部分" + med[2]
                + "（依从率约 " + Math.round(100.0 * med[0] / total) + "%）";
    }

    private List<String> trimList(List<String> list, int max) {
        if (list.size() <= max) {
            return list;
        }
        return list.subList(list.size() - max, list.size());
    }
}
