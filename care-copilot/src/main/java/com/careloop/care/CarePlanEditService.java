package com.careloop.care;

import com.careloop.feishu.FeishuMessageService;
import com.careloop.ortho.OrthoKnowledgeService;
import com.careloop.patient.PatientContextService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 在飞书内用自然语言/结构化指令更新随访计划（不跳转 H5）。
 */
@Service
public class CarePlanEditService {

    private static final Pattern DAY_LINE = Pattern.compile(
            "(?m)^\\s*D\\s*(\\d+)\\s*[：:\\.、\\-]?\\s*(.+?)\\s*$"
    );
    /** 随访天数：14 / 随访时间改成1天 / 改为 7 天 */
    private static final Pattern FOLLOWUP_DAYS = Pattern.compile(
            "(?:随访天数|随访日数|随访时间|随访周期|随访)\\s*(?:改成|改为|调整为|设为|设置为|定为)?\\s*[：:]?\\s*(\\d{1,3})\\s*天"
                    + "|(?:改成|改为|调整为|设为|设置为)\\s*(\\d{1,3})\\s*天"
    );
    /** 每日上午9.55分 / 每天 9:55 / 每日时间：9点55分 / 每日时间：20点 */
    private static final Pattern DAILY_TIME = Pattern.compile(
            "(?:每日时间|每天时间|收集时间)?\\s*"
                    + "(?:每日|每天)?\\s*(?:上午|下午|早上|晚上)?"
                    + "\\s*(\\d{1,2})\\s*[:.：点]\\s*(\\d{1,2})\\s*分?"
                    + "|(?:每日时间|每天时间)\\s*[：:]?\\s*(\\d{1,2})\\s*点?"
                    + "|(?:每日|每天)\\s*(\\d{1,2})\\s*点(?!\\s*\\d)"
    );
    private static final Pattern COLLECT_QUESTIONS = Pattern.compile(
            "(?:收集一下|收集|提问|问一下|问卷)[:：]?\\s*(.+)$"
    );

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final FeishuMessageService feishuMessageService;
    private final PatientContextService patientContextService;
    private final OrthoKnowledgeService knowledgeService;

    public CarePlanEditService(JdbcTemplate jdbcTemplate,
                               ObjectMapper objectMapper,
                               FeishuMessageService feishuMessageService,
                               PatientContextService patientContextService,
                               OrthoKnowledgeService knowledgeService) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.feishuMessageService = feishuMessageService;
        this.patientContextService = patientContextService;
        this.knowledgeService = knowledgeService;
    }

    @Transactional
    public Map<String, Object> applyDoctorText(long planId, String rawText, String chatId) {
        Map<String, Object> plan = loadPlan(planId);
        if (plan == null) {
            return Map.of("ok", false, "message", "计划不存在: " + planId);
        }
        String status = String.valueOf(plan.get("status"));
        if (!"DRAFT".equals(status) && !"ACTIVE".equals(status)) {
            return Map.of("ok", false, "message", "计划状态不可改: " + status);
        }

        ObjectNode content;
        try {
            content = (ObjectNode) objectMapper.readTree(String.valueOf(plan.get("contentJson")));
        } catch (Exception e) {
            content = objectMapper.createObjectNode();
            content.putArray("nodes");
        }

        String text = rawText == null ? "" : rawText.trim();
        List<String> changes = new ArrayList<>();
        long patientIdEarly = ((Number) plan.get("patientId")).longValue();
        var patientEarly = patientContextService.loadById(patientIdEarly)
                .orElse(Map.of("name", "患者", "diagnosis", ""));

        // 0) 随访天数
        Matcher daysM = FOLLOWUP_DAYS.matcher(text);
        if (daysM.find()) {
            String g1 = daysM.group(1);
            String g2 = daysM.group(2);
            int days = Integer.parseInt(g1 != null ? g1 : g2);
            days = Math.max(1, Math.min(days, 90));
            content.put("followupDays", days);
            ArrayNode nodes = content.putArray("nodes");
            knowledgeService.fillPlanNodes(
                    nodes,
                    String.valueOf(patientEarly.getOrDefault("name", "患者")),
                    String.valueOf(patientEarly.getOrDefault("diagnosis", "")),
                    days
            );
            changes.add("已设定随访 " + days + " 天，并按此重排随访日程");
        }

        // 随访采集开关（兼容旧「每日收集」说法）
        if (text.contains("关闭每日收集") || text.contains("停止每日收集") || text.contains("每日收集：关")
                || text.contains("每日收集:关") || text.contains("不每日收集")
                || text.contains("关闭随访采集") || text.contains("停止随访")) {
            content.put("dailyCollectEnabled", false);
            changes.add("已关闭随访采集");
        } else if (text.contains("开启每日收集") || text.contains("每日收集：开") || text.contains("每日收集:开")
                || text.contains("打开每日收集") || text.contains("要每日收集")
                || text.contains("每日收集") || text.contains("每天收集") || text.contains("日常问题")
                || text.contains("开启随访采集")
                || text.matches("(?s).*每日.*收集.*") || text.matches("(?s).*每天.*收集.*")) {
            content.put("dailyCollectEnabled", true);
            if (changes.stream().noneMatch(c -> c.contains("随访采集") || c.contains("每日收集"))) {
                changes.add("已开启随访采集");
            }
        }

        // 医生加项 / 旧「每日问题」→ 记入加项并刷新统一表单预览
        String dailyQ = extractPrefixed(text, "每日问题", "每日收集问题", "每日问卷", "日常问题", "随访问题");
        if (dailyQ != null) {
            content.put("dailyCollectEnabled", true);
            appendDoctorExtra(content, dailyQ);
            refreshUnifiedForm(content);
            changes.add("已记入随访加项并刷新表单");
        }

        // 每日时间（含 9.55 / 9:55）
        Matcher hourM = DAILY_TIME.matcher(text.replace('．', '.'));
        if (hourM.find()) {
            Integer hour = null;
            Integer minute = 0;
            if (hourM.group(1) != null) {
                hour = Integer.parseInt(hourM.group(1));
                minute = Integer.parseInt(hourM.group(2));
            } else if (hourM.group(3) != null) {
                hour = Integer.parseInt(hourM.group(3));
            } else if (hourM.group(4) != null) {
                hour = Integer.parseInt(hourM.group(4));
            }
            if (hour != null && hour >= 0 && hour <= 23) {
                if (text.contains("下午") && hour < 12) {
                    hour += 12;
                }
                minute = Math.max(0, Math.min(minute == null ? 0 : minute, 59));
                content.put("dailyCollectEnabled", true);
                content.put("dailyCollectHour", hour);
                content.put("dailyCollectMinute", minute);
                changes.add("随访采集时间改为 " + String.format("%d:%02d", hour, minute));
            }
        }

        // 自然语言：收集一下…… → 当作医生加项
        if (dailyQ == null) {
            Matcher qM = COLLECT_QUESTIONS.matcher(text);
            if (qM.find()) {
                String q = qM.group(1).trim();
                q = q.replaceFirst("^(今日|今天|每天|每日)", "").trim();
                if (q.length() >= 4) {
                    content.put("dailyCollectEnabled", true);
                    appendDoctorExtra(content, q);
                    refreshUnifiedForm(content);
                    changes.add("已记入随访加项并刷新表单");
                }
            }
        }

        // 1) 改某日节点：D3：……
        Matcher dayMatcher = DAY_LINE.matcher(text);
        while (dayMatcher.find()) {
            int day = Integer.parseInt(dayMatcher.group(1));
            String question = dayMatcher.group(2).trim();
            upsertNode(content, day, question);
            changes.add("已更新 D" + day + " 随访问题");
        }

        // 2) 仅当明确「加要求」前缀时写入补充要求（避免整段修改被塞进补充）
        String extra = extractPrefixed(text,
                "加要求", "补充要求", "医生要求", "额外要求", "医嘱补充", "补充医嘱", "备注", "加一句");
        if (extra != null) {
            appendDoctorExtra(content, extra);
            changes.add("已加入医生要求：" + trim(extra, 80));
        }

        // 3) 整体替换照护要点
        String tips = extractPrefixed(text, "照护要点", "更新要点", "改要点");
        if (tips != null) {
            content.put("careTips", tips);
            changes.add("已更新照护要点");
        }

        // 4) 纯自然语言且未命中结构：整段当作医生要求追加
        if (changes.isEmpty() && text.length() >= 4) {
            appendDoctorExtra(content, text);
            changes.add("已将您的说明记入本病历随访要求");
        }

        if (changes.isEmpty()) {
            return Map.of("ok", false, "message", "未识别到可更新内容。可发：\n"
                    + "随访时间改成1天，每日上午9:55收集疼痛、用药、伤口瘙痒\n"
                    + "或：随访天数：14 / 开启每日收集 / 每日问题：…… / D3：……");
        }

        jdbcTemplate.update(
                "UPDATE care_plan SET content_json = CAST(? AS JSON), updated_at = NOW() WHERE id = ?",
                content.toString(), planId
        );

        long patientId = ((Number) plan.get("patientId")).longValue();
        var patient = patientContextService.loadById(patientId).orElse(Map.of("name", "患者", "diagnosis", ""));
        String name = String.valueOf(patient.getOrDefault("name", "患者"));
        String diagnosis = String.valueOf(patient.getOrDefault("diagnosis", ""));

        String msgId = null;
        if (chatId != null && !chatId.isBlank()) {
            msgId = feishuMessageService.sendCarePlanConfirmCard(
                    "chat_id", chatId, name, diagnosis, planId, content, null
            );
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("planId", planId);
        result.put("patientId", patientId);
        result.put("changes", changes);
        result.put("messageId", msgId);
        result.put("message", "计划已更新（患者#" + patientId + " " + name + "）：" + String.join("；", changes));
        return result;
    }

    public String editGuide(long planId, String patientName) {
        return "【修改离院表单 · 患者 " + patientName + " · 计划#" + planId + "】\n"
                + "表单字段：科室｜姓名｜病症｜随访天数｜日常记录时间｜日常问题表单｜随访记录点｜医生补充\n"
                + "问题均为「选择题 + 自定义描述」。可直接说：\n"
                + "• 随访天数：7\n"
                + "• 每日时间：9:55\n"
                + "• 每日问题：疼痛、用药、伤口渗液\n"
                + "• D3：加问小腿是否单侧胀痛\n"
                + "• 加要求：两周内禁止泡澡\n"
                + "改完后点卡片「确认出院并生成随访码」。";
    }

    private String normalizeDailyQ(String q) {
        if (q == null) {
            return "";
        }
        String t = q.trim();
        return t.startsWith("【每日收集】") ? t : "【每日收集】" + t;
    }

    private void upsertNode(ObjectNode content, int day, String question) {
        ArrayNode nodes = content.has("nodes") && content.path("nodes").isArray()
                ? (ArrayNode) content.path("nodes")
                : content.putArray("nodes");
        ObjectNode form = knowledgeService.forms().formFromFocus(
                List.of("wound", "pain", "meds"), "D" + day + " 医生定制");
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).path("day").asInt() == day) {
                ObjectNode node = (ObjectNode) nodes.get(i);
                node.put("question", question);
                if (!node.has("form")) {
                    node.set("form", form);
                }
                return;
            }
        }
        ObjectNode n = nodes.addObject();
        n.put("day", day);
        n.put("title", "D" + day + " 医生定制");
        n.put("question", question);
        n.set("form", form);
    }

    private void appendDoctorExtra(ObjectNode content, String extra) {
        ArrayNode extras = content.has("doctorExtras") && content.path("doctorExtras").isArray()
                ? (ArrayNode) content.path("doctorExtras")
                : content.putArray("doctorExtras");
        extras.add(extra.trim());
        refreshUnifiedForm(content);
    }

    private void refreshUnifiedForm(ObjectNode content) {
        String diagnosis = content.path("diagnosis").asText("");
        var disease = knowledgeService.resolveDisease(diagnosis);
        if (content.hasNonNull("diseaseId")) {
            var byId = knowledgeService.disease(content.path("diseaseId").asText());
            if (byId != null) {
                disease = byId;
            }
        }
        List<String> extras = new ArrayList<>();
        if (content.path("doctorExtras").isArray()) {
            for (var e : content.path("doctorExtras")) {
                String q = e.isTextual() ? e.asText() : e.path("question").asText("");
                if (q != null && !q.isBlank()) {
                    extras.add(q.trim());
                }
            }
        }
        var form = knowledgeService.buildFormForDay(disease, 1, "", extras, null);
        content.set("followupForm", form);
        content.set("dailyCollectForm", form);
        content.put("dailyCollectQuestion",
                knowledgeService.forms().summarizeQuestion("术后随访", form));
        content.put("unifiedFollowup", true);
    }

    private String extractPrefixed(String text, String... keys) {
        for (String key : keys) {
            Pattern p = Pattern.compile("(?i)(?:" + Pattern.quote(key) + ")\\s*[：:]\\s*(.+)");
            Matcher m = p.matcher(text);
            if (m.find()) {
                return m.group(1).trim();
            }
        }
        return null;
    }

    private Map<String, Object> loadPlan(long planId) {
        return jdbcTemplate.query(
                "SELECT id, patient_id, status, content_json FROM care_plan WHERE id = ?",
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("planId", rs.getLong("id"));
                    m.put("patientId", rs.getLong("patient_id"));
                    m.put("status", rs.getString("status"));
                    m.put("contentJson", rs.getString("content_json"));
                    return m;
                },
                planId
        );
    }

    private String trim(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
