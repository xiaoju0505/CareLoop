package com.careloop.triage;

import com.careloop.collect.FollowupFormService;
import com.careloop.collect.StructuredCollectService;
import com.careloop.ortho.OrthoKnowledgeService;
import com.careloop.patient.DeviceMockService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 骨科术后红黄绿分级——文本规则 + 表单信号 + 结构化字段 + 手环数据，可解释。
 */
@Service
public class TriageService {

    public record TriageResult(
            String level,
            String reason,
            String category,
            String ruleId,
            String matchedKeyword,
            String suggestedAction,
            String evidenceSummary
    ) {
        public TriageResult(String level, String reason, String category) {
            this(level, reason, category, "", "", "继续按计划观察", reason);
        }
    }

    private final OrthoKnowledgeService knowledgeService;
    private final StructuredCollectService structuredCollectService;
    private final DeviceMockService deviceMockService;
    private final FollowupFormService followupFormService;

    public TriageService(OrthoKnowledgeService knowledgeService,
                         StructuredCollectService structuredCollectService,
                         DeviceMockService deviceMockService,
                         FollowupFormService followupFormService) {
        this.knowledgeService = knowledgeService;
        this.structuredCollectService = structuredCollectService;
        this.deviceMockService = deviceMockService;
        this.followupFormService = followupFormService;
    }

    public TriageResult triage(String text) {
        return triage(text, 0L);
    }

    public TriageResult triage(String text, long patientId) {
        Map<String, Object> fields = structuredCollectService.extract(text);
        OrthoKnowledgeService.TriageHit hit = knowledgeService.triage(text);

        String formSignal = followupFormService.highestSignalFromAnswer(text, null);
        if (rank(formSignal) > rank(hit.level())) {
            hit = new OrthoKnowledgeService.TriageHit(
                    formSignal.toUpperCase(Locale.ROOT),
                    "表单选项信号：" + formSignal + " | 原文：" + text,
                    "表单信号",
                    "form-signal-map",
                    formSignal,
                    defaultAction(formSignal)
            );
        }

        if (fields.get("painScore") instanceof Number n) {
            int pain = n.intValue();
            if (pain >= 8 && rank(hit.level()) < rank("YELLOW")) {
                hit = new OrthoKnowledgeService.TriageHit(
                        "YELLOW",
                        "预警（疼痛预警）：结构化疼痛≈剧痛 | 原文：" + text,
                        "疼痛预警",
                        "struct-pain-severe",
                        "剧痛",
                        "评估镇痛方案，必要时提前复诊"
                );
            } else if (pain >= 5 && "GREEN".equalsIgnoreCase(hit.level())) {
                hit = new OrthoKnowledgeService.TriageHit(
                        "YELLOW",
                        "预警（疼痛预警）：结构化疼痛≈中度 | 原文：" + text,
                        "疼痛预警",
                        "struct-pain-moderate",
                        "中度疼痛",
                        "关注镇痛效果与睡眠"
                );
            }
        }
        if ("SEVERE".equals(fields.get("woundStatus")) && rank(hit.level()) < rank("RED")) {
            hit = new OrthoKnowledgeService.TriageHit(
                    "RED",
                    "急症预警（严重感染/出血）：结构化伤口=SEVERE | 原文：" + text,
                    "严重感染/出血/功能丧失",
                    "struct-wound-severe",
                    String.valueOf(fields.get("woundStatus")),
                    "建议立即就医或急诊评估"
            );
        }

        if (patientId > 0) {
            Map<String, Object> device = safeDevice(patientId);
            hit = applyDeviceRules(hit, text, fields, device);
        }

        String evidence = buildEvidence(text, fields, hit, formSignal);
        String action = hit.suggestedAction() == null || hit.suggestedAction().isBlank()
                ? defaultAction(hit.level())
                : hit.suggestedAction();

        return new TriageResult(
                hit.level(),
                hit.reason(),
                hit.category(),
                hit.ruleId() == null ? "" : hit.ruleId(),
                hit.matchedKeyword() == null ? "" : hit.matchedKeyword(),
                action,
                evidence
        );
    }

    private OrthoKnowledgeService.TriageHit applyDeviceRules(OrthoKnowledgeService.TriageHit hit,
                                                             String text,
                                                             Map<String, Object> fields,
                                                             Map<String, Object> device) {
        if (device == null || device.isEmpty()) {
            return hit;
        }
        int resting = asInt(device.get("restingHeartRate"), asInt(device.get("heartRate"), 0));
        int spo2 = asInt(device.get("spo2"), 100);
        boolean dyspnea = Boolean.TRUE.equals(fields.get("dyspnea"))
                || containsAny(text, "气短", "气促", "喘不上", "呼吸困难", "胸闷");

        if (spo2 > 0 && spo2 < 92 && rank(hit.level()) < rank("RED")) {
            return new OrthoKnowledgeService.TriageHit(
                    "RED",
                    "急症预警（手环+血氧）：SpO₂=" + spo2 + "%（<92） | 原文：" + text,
                    "心肺/血栓急症",
                    "device-spo2-lt92",
                    "血氧" + spo2 + "%",
                    "建议立即就医，评估缺氧/肺栓塞风险"
            );
        }
        if (resting >= 90 && dyspnea && rank(hit.level()) < rank("YELLOW")) {
            return new OrthoKnowledgeService.TriageHit(
                    "YELLOW",
                    "预警（手环+主诉联合）：静息心率=" + resting + "bpm（≥90）且主诉气短/胸闷 | 原文：" + text,
                    "手环+主诉联合预警",
                    "device-hr-dyspnea",
                    "静息心率" + resting + "+气短",
                    "电话随访或提前复诊，排除心肺并发症"
            );
        }
        if (resting >= 100 && rank(hit.level()) < rank("YELLOW")) {
            return new OrthoKnowledgeService.TriageHit(
                    "YELLOW",
                    "预警（手环心率）：静息心率=" + resting + "bpm（≥100） | 原文：" + text,
                    "手环生命体征预警",
                    "device-rhr-ge100",
                    "静息心率" + resting,
                    "结合症状复核，必要时复诊"
            );
        }
        return hit;
    }

    private Map<String, Object> safeDevice(long patientId) {
        try {
            Map<String, Object> d = deviceMockService.findLatest(patientId);
            return d == null ? Map.of() : d;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String buildEvidence(String text, Map<String, Object> fields,
                                 OrthoKnowledgeService.TriageHit hit, String formSignal) {
        StringBuilder sb = new StringBuilder();
        sb.append("依据回复：").append(trim(text, 80));
        if (formSignal != null && !"GREEN".equalsIgnoreCase(formSignal)) {
            sb.append("\n表单信号：").append(formSignal);
        }
        if (!fields.isEmpty()) {
            sb.append("\n结构化：");
            fields.forEach((k, v) -> sb.append(k).append("=").append(v).append(" "));
        }
        if (hit.ruleId() != null && !hit.ruleId().isBlank()) {
            sb.append("\n知识库规则：").append(hit.ruleId());
            if (hit.category() != null && !hit.category().isBlank()) {
                sb.append(" / ").append(hit.category());
            }
        } else if (hit.category() != null && !hit.category().isBlank()) {
            sb.append("\n知识库类别：").append(hit.category());
        }
        if (hit.matchedKeyword() != null && !hit.matchedKeyword().isBlank()) {
            sb.append("\n命中关键词：").append(hit.matchedKeyword());
        }
        return sb.toString().trim();
    }

    private String defaultAction(String level) {
        return switch (level == null ? "" : level.toUpperCase(Locale.ROOT)) {
            case "RED" -> "建议立即电话随访或急诊评估";
            case "YELLOW" -> "建议今日内复核，必要时提前复诊";
            default -> "继续按计划观察与采集";
        };
    }

    private int rank(String level) {
        return switch (level == null ? "" : level.toUpperCase(Locale.ROOT)) {
            case "RED" -> 3;
            case "YELLOW" -> 2;
            case "GREEN" -> 1;
            default -> 0;
        };
    }

    private int asInt(Object o, int def) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o != null) {
            try {
                return Integer.parseInt(String.valueOf(o).replaceAll("[^0-9]", ""));
            } catch (Exception ignored) {
            }
        }
        return def;
    }

    private boolean containsAny(String t, String... keys) {
        if (t == null) {
            return false;
        }
        for (String k : keys) {
            if (t.contains(k)) {
                return true;
            }
        }
        return false;
    }

    private String trim(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    public Map<String, Object> toMap(TriageResult result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("level", result.level());
        m.put("reason", result.reason());
        m.put("category", result.category() == null ? "" : result.category());
        m.put("ruleId", result.ruleId() == null ? "" : result.ruleId());
        m.put("matchedKeyword", result.matchedKeyword() == null ? "" : result.matchedKeyword());
        m.put("suggestedAction", result.suggestedAction() == null ? "" : result.suggestedAction());
        m.put("evidenceSummary", result.evidenceSummary() == null ? "" : result.evidenceSummary());
        return m;
    }
}
