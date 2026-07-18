package com.careloop.ortho;

import com.careloop.collect.FollowupFormService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * 骨科知识库：通用层 + 多病种包。分诊规则 = 通用 ∪ 病种；表单 = 通用 A + 病种 B。
 */
@Service
public class OrthoKnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(OrthoKnowledgeService.class);
    private static final String COMMON_PATH = "kb/ortho-common.json";
    private static final String DISEASE_GLOB = "classpath*:kb/diseases/*.json";

    private final ObjectMapper objectMapper;
    private final FollowupFormService followupFormService;
    private volatile JsonNode common;
    private volatile Map<String, JsonNode> diseases = Map.of();
    private volatile JsonNode root;
    private volatile String source = "local-json";
    private volatile String activeDiseaseId = "tka-knee";

    public OrthoKnowledgeService(ObjectMapper objectMapper, FollowupFormService followupFormService) {
        this.objectMapper = objectMapper;
        this.followupFormService = followupFormService;
    }

    @PostConstruct
    public void load() {
        try {
            try (InputStream in = new org.springframework.core.io.ClassPathResource(COMMON_PATH).getInputStream()) {
                common = objectMapper.readTree(in);
            }
            Map<String, JsonNode> map = new LinkedHashMap<>();
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            for (Resource r : resolver.getResources(DISEASE_GLOB)) {
                try (InputStream in = r.getInputStream()) {
                    JsonNode d = objectMapper.readTree(in);
                    String id = d.path("id").asText();
                    if (!id.isBlank()) {
                        map.put(id, d);
                    }
                }
            }
            diseases = map;
            activeDiseaseId = map.containsKey("tka-knee") ? "tka-knee"
                    : map.keySet().stream().findFirst().orElse("");
            rebuildMergedRoot(activeDiseaseId);
            source = "local-json";
            log.info("已加载骨科知识库: common={} diseases={} active={}",
                    common.path("version").asText(), map.keySet(), activeDiseaseId);
        } catch (Exception e) {
            throw new IllegalStateException("加载骨科知识库失败", e);
        }
    }

    /** 外部同步：仍接受旧单文件结构，写入默认病种或重建合并视图 */
    public synchronized void replaceRoot(JsonNode newRoot, String newSource) {
        if (newRoot == null || newRoot.isMissingNode()) {
            throw new IllegalArgumentException("知识库内容为空");
        }
        this.root = newRoot;
        this.source = newSource == null ? "external" : newSource;
        if (newRoot.has("specialtyItems") || newRoot.has("matchKeywords")) {
            String id = newRoot.path("id").asText("external-disease");
            Map<String, JsonNode> copy = new LinkedHashMap<>(diseases);
            copy.put(id, newRoot);
            diseases = copy;
            activeDiseaseId = id;
        }
        log.info("知识库已切换来源={} title={}", source, title());
    }

    public String source() {
        return source;
    }

    public JsonNode root() {
        return root;
    }

    public JsonNode common() {
        return common;
    }

    public JsonNode disease(String id) {
        if (id == null || id.isBlank()) {
            return diseases.get(activeDiseaseId);
        }
        return diseases.getOrDefault(id, diseases.get(activeDiseaseId));
    }

    public JsonNode resolveDisease(String diagnosis) {
        JsonNode fallback = null;
        JsonNode best = null;
        int bestPriority = -1;
        String d = diagnosis == null ? "" : diagnosis;
        String dl = d.toLowerCase(Locale.ROOT);
        for (JsonNode pack : diseases.values()) {
            if (pack.path("isFallback").asBoolean(false)) {
                fallback = pack;
                continue;
            }
            if (excluded(pack, d, dl)) {
                continue;
            }
            if (!matches(pack, d, dl)) {
                continue;
            }
            int pr = pack.path("priority").asInt(10);
            if (pr > bestPriority) {
                bestPriority = pr;
                best = pack;
            }
        }
        if (best != null) {
            return best;
        }
        return fallback != null ? fallback : disease(activeDiseaseId);
    }

    private boolean excluded(JsonNode pack, String d, String dl) {
        for (JsonNode kw : pack.path("excludeKeywords")) {
            String k = kw.asText("");
            if (!k.isBlank() && (d.contains(k) || dl.contains(k.toLowerCase(Locale.ROOT)))) {
                return true;
            }
        }
        return false;
    }

    private boolean matches(JsonNode pack, String d, String dl) {
        for (JsonNode kw : pack.path("matchKeywords")) {
            String k = kw.asText("");
            if (!k.isBlank() && (d.contains(k) || dl.contains(k.toLowerCase(Locale.ROOT)))) {
                return true;
            }
        }
        return false;
    }

    public ObjectNode buildFormForDay(JsonNode disease, int day, String dayTitle,
                                      List<String> doctorExtras, JsonNode modulesOverride) {
        return buildFormForDay(disease, day, dayTitle, doctorExtras, modulesOverride, false);
    }

    public ObjectNode buildFormForDay(JsonNode disease, int day, String dayTitle,
                                      List<String> doctorExtras, JsonNode modulesOverride,
                                      boolean shortMode) {
        return followupFormService.buildFollowupForm(
                common, disease, day, dayTitle, doctorExtras, modulesOverride, shortMode);
    }

    /** 近 N 次已答且分诊偏绿 → 可用简表 */
    public boolean preferShortForm(long consecutiveGreenNeeded) {
        return consecutiveGreenNeeded >= common.path("shortForm").path("consecutiveGreenDays").asInt(3);
    }

    public List<Map<String, Object>> listDiseases() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (JsonNode d : diseases.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.path("id").asText());
            m.put("title", d.path("title").asText());
            m.put("specialty", d.path("specialty").asText());
            m.put("fallback", d.path("isFallback").asBoolean(false));
            list.add(m);
        }
        return list;
    }

    public String title() {
        JsonNode d = disease(activeDiseaseId);
        if (d != null) {
            return d.path("title").asText(common.path("title").asText("骨科知识库"));
        }
        return root.path("title").asText("骨科知识库");
    }

    public String specialty() {
        JsonNode d = disease(activeDiseaseId);
        if (d != null && d.hasNonNull("specialty")) {
            return d.path("specialty").asText();
        }
        return common.path("specialty").asText(OrthoCareTemplates.SPECIALTY);
    }

    public String careTips() {
        JsonNode d = disease(activeDiseaseId);
        if (d != null && d.hasNonNull("careTips") && !d.path("careTips").asText("").isBlank()) {
            return d.path("careTips").asText();
        }
        return common.path("careTips").asText("抬高患肢、伤口干燥、防跌倒、按医嘱用药与锻炼");
    }

    public String welcomeMessage(String patientName, String diagnosis) {
        JsonNode pack = resolveDisease(diagnosis);
        String tips = pack != null && pack.hasNonNull("careTips")
                ? pack.path("careTips").asText()
                : careTips();
        return "您好" + safe(patientName) + "，我是您的骨科术后随访助手。"
                + "已为您绑定「" + safe(blankToDefault(diagnosis, OrthoCareTemplates.DEFAULT_DIAGNOSIS)) + "」连续照护"
                + (pack != null ? "（病种：" + pack.path("title").asText() + "）" : "")
                + "。我会按医生设定的计划，用同一张随访表收集恢复情况。"
                + "请在收到提醒后一次答完即可。若出现胸闷气促、高热、大量渗液或单侧肢体明显胀痛发紫，请立即就医并告知医护。";
    }

    public void fillPlanNodes(ArrayNode nodes) {
        fillPlanNodes(nodes, null, null, 14);
    }

    public void fillPlanNodes(ArrayNode nodes, String patientName, String diagnosis) {
        fillPlanNodes(nodes, patientName, diagnosis, 14);
    }

    /**
     * 生成随访日程（每天至多一条）。内容写入 nodes 供飞书展示；
     * 实际表单在 confirm 时按病种统一合成。
     */
    public void fillPlanNodes(ArrayNode nodes, String patientName, String diagnosis, int followupDays) {
        JsonNode pack = resolveDisease(diagnosis);
        rebuildMergedRoot(pack == null ? activeDiseaseId : pack.path("id").asText(activeDiseaseId));
        int days = followupDays <= 0 ? 14 : followupDays;
        for (Integer day : buildScheduleDays(days, pack)) {
            ObjectNode item = nodes.addObject();
            item.put("day", day);
            String title = dayTitle(pack, day);
            item.put("title", title);
            ObjectNode form = buildFormForDay(pack, day, title, List.of(), null);
            item.set("form", form);
            item.put("question", followupFormService.summarizeQuestion(
                    "术后随访 · D" + day + (title.isBlank() ? "" : " · " + title), form));
            item.put("expand", isExpandDay(pack, day));
        }
    }

    public TreeSet<Integer> buildScheduleDays(int followupDays, JsonNode disease) {
        TreeSet<Integer> set = new TreeSet<>();
        int dense = common.path("schedulePolicy").path("denseThroughDay").asInt(7);
        int step = common.path("schedulePolicy").path("afterDenseStep").asInt(2);
        if (step < 1) {
            step = 2;
        }
        for (int d = 1; d <= Math.min(dense, followupDays); d++) {
            set.add(d);
        }
        for (int d = dense + 1; d <= followupDays; d += step) {
            set.add(d);
        }
        if (disease != null) {
            for (JsonNode n : disease.path("expandDays")) {
                int day = n.asInt();
                if (day >= 1 && day <= followupDays) {
                    set.add(day);
                }
            }
        }
        if (set.isEmpty() && followupDays >= 1) {
            set.add(1);
        }
        return set;
    }

    public void enrichPlanMeta(ObjectNode content, String diagnosis, String doctorName) {
        enrichPlanMeta(content, diagnosis, doctorName, null);
    }

    public void enrichPlanMeta(ObjectNode content, String diagnosis, String doctorName, String caseNotes) {
        JsonNode pack = resolveDisease(diagnosis);
        String diseaseId = pack == null ? activeDiseaseId : pack.path("id").asText(activeDiseaseId);
        rebuildMergedRoot(diseaseId);

        String dx = blankToDefault(diagnosis, OrthoCareTemplates.DEFAULT_DIAGNOSIS);
        content.put("specialty", pack != null ? pack.path("specialty").asText(specialty()) : specialty());
        content.put("diagnosis", dx);
        content.put("doctorName", doctorName == null ? "" : doctorName);
        content.put("careTips", personalizedCareTips(dx, caseNotes));
        content.put("knowledgeBaseId", common.path("id").asText() + "+" + diseaseId);
        content.put("knowledgeBaseVersion", common.path("version").asText() + "/"
                + (pack == null ? "-" : pack.path("version").asText()));
        content.put("diseaseId", diseaseId);
        content.put("diseaseTitle", pack == null ? "骨科术后" : pack.path("title").asText());

        int defDays = common.path("schedulePolicy").path("defaultFollowupDays").asInt(14);
        if (!content.has("followupDays")) {
            content.put("followupDays", defDays);
        }
        // 兼容旧字段名：统一为「随访采集时间」
        if (!content.has("dailyCollectEnabled")) {
            content.put("dailyCollectEnabled", true);
        }
        if (!content.has("dailyCollectHour")) {
            content.put("dailyCollectHour",
                    common.path("schedulePolicy").path("defaultCollectHour").asInt(20));
        }
        if (!content.has("dailyCollectMinute")) {
            content.put("dailyCollectMinute",
                    common.path("schedulePolicy").path("defaultCollectMinute").asInt(0));
        }

        ObjectNode preview = buildFormForDay(pack, 1, dayTitle(pack, 1), List.of(), null);
        content.set("followupForm", preview);
        // 兼容旧卡片字段
        content.set("dailyCollectForm", preview);
        content.put("dailyCollectQuestion",
                followupFormService.summarizeQuestion("术后随访", preview));
        content.put("unifiedFollowup", true);
    }

    public String defaultDailyQuestion() {
        ObjectNode form = buildFormForDay(disease(activeDiseaseId), 1, "", List.of(), null);
        return followupFormService.summarizeQuestion("术后随访", form);
    }

    public FollowupFormService forms() {
        return followupFormService;
    }

    public String displayText(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return raw
                .replaceAll("【骨科知识库D\\d+[^】]*】", "")
                .replaceAll("【骨科知识库[^】]*】", "")
                .replace("根据骨科知识库，", "")
                .replace("知识库提示", "随访提示")
                .replace("知识库判定为", "当前判定为")
                .replace("已结合知识库规则查看", "已查看")
                .replace("知识库急症", "急症预警")
                .replace("知识库预警", "预警")
                .replace("知识库规则命中", "规则命中")
                .replace("骨科知识库：", "")
                .trim();
    }

    public String personalizedCareTips(String diagnosis, String caseNotes) {
        JsonNode pack = resolveDisease(diagnosis);
        String base = pack != null && pack.hasNonNull("careTips")
                ? pack.path("careTips").asText() : careTips();
        String orders = extractOrders(caseNotes);
        StringBuilder sb = new StringBuilder();
        if (diagnosis != null && !diagnosis.isBlank()) {
            sb.append("针对「").append(diagnosis).append("」：");
        }
        if (orders != null && !orders.isBlank()) {
            sb.append("病历医嘱——").append(orders);
            if (!orders.endsWith("。") && !orders.endsWith("；")) {
                sb.append("。");
            }
            sb.append("同时注意：").append(base);
        } else {
            sb.append(base);
        }
        return sb.toString();
    }

    private String extractOrders(String caseNotes) {
        if (caseNotes == null || caseNotes.isBlank()) {
            return "";
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?:出院医嘱|医嘱|注意事项)[:：]?\\s*([^\\n]{2,180})")
                .matcher(caseNotes);
        if (m.find()) {
            return m.group(1).trim();
        }
        String t = caseNotes.replaceAll("\\[来源附件\\][^\\n]*", "").trim();
        if (t.length() > 12 && t.length() <= 200
                && (t.contains("抬高") || t.contains("抗凝") || t.contains("伤口") || t.contains("锻炼"))) {
            return t.length() > 120 ? t.substring(0, 120) + "…" : t;
        }
        return "";
    }

    public TriageHit triage(String text) {
        if (text == null || text.isBlank()) {
            return new TriageHit("GREEN", "空消息", "none", "empty", "", "等待随访采集");
        }
        String t = text.toLowerCase(Locale.ROOT);
        JsonNode rules = root.path("triageRules");
        for (JsonNode rule : rules) {
            if (!"RED".equalsIgnoreCase(rule.path("level").asText())) {
                continue;
            }
            String kw = matchedKeyword(t, rule.path("keywords"));
            if (kw != null) {
                return toHit(rule, text, kw);
            }
        }
        for (JsonNode rule : rules) {
            if (!"YELLOW".equalsIgnoreCase(rule.path("level").asText())) {
                continue;
            }
            String kw = matchedKeyword(t, rule.path("keywords"));
            if (kw != null) {
                return toHit(rule, text, kw);
            }
        }
        return new TriageHit("GREEN", "未见明显异常信号", "平稳", "green-default", "", "继续按计划观察与采集");
    }

    private TriageHit toHit(JsonNode rule, String text, String kw) {
        String level = rule.path("level").asText("YELLOW").toUpperCase(Locale.ROOT);
        String category = rule.path("category").asText("预警");
        String ruleId = rule.path("id").asText("");
        if (ruleId.isBlank()) {
            ruleId = level.toLowerCase(Locale.ROOT) + "-" + category.replace("/", "-");
        }
        String action = rule.path("suggestedAction").asText("");
        if (action.isBlank()) {
            action = "RED".equals(level)
                    ? "建议立即电话随访或急诊评估"
                    : "建议今日内复核，必要时提前复诊";
        }
        return new TriageHit(level, formatReason(rule, text), category, ruleId, kw, action);
    }

    public String replyForLevel(String level) {
        JsonNode replies = root.path("patientReplies");
        if (!replies.isObject() || replies.isEmpty()) {
            replies = common.path("patientReplies");
        }
        String raw = switch (level == null ? "" : level.toUpperCase(Locale.ROOT)) {
            case "RED" -> replies.path("red").asText(OrthoCareTemplates.redReply());
            case "YELLOW" -> replies.path("yellow").asText(OrthoCareTemplates.yellowReply());
            default -> replies.path("green").asText(OrthoCareTemplates.greenReply());
        };
        return displayText(raw);
    }

    public String notifyAfterDoctor() {
        JsonNode replies = root.path("patientReplies");
        if (!replies.isObject()) {
            replies = common.path("patientReplies");
        }
        return displayText(replies.path("notifyAfterDoctor")
                .asText(OrthoCareTemplates.notifyPatientAfterDoctor()));
    }

    public String earlyRecheckMessage() {
        JsonNode replies = root.path("patientReplies");
        if (!replies.isObject()) {
            replies = common.path("patientReplies");
        }
        return replies.path("earlyRecheck")
                .asText("医生建议您提前复诊，请尽快联系骨科门诊。");
    }

    public String briefingAskListMarkdown() {
        StringBuilder sb = new StringBuilder();
        for (JsonNode item : root.path("briefingAskList")) {
            sb.append("- ").append(item.asText()).append("\n");
        }
        return sb.toString();
    }

    public String toFeishuMarkdown() {
        StringBuilder md = new StringBuilder();
        md.append("# ").append(title()).append("\n\n");
        md.append("版本：").append(root.path("version").asText()).append("\n");
        md.append(common.path("disclaimer").asText()).append("\n\n");
        md.append("## 照护要点\n").append(careTips()).append("\n\n");
        md.append("## 病种包\n");
        for (Map<String, Object> d : listDiseases()) {
            md.append("- ").append(d.get("id")).append("：").append(d.get("title")).append("\n");
        }
        md.append("\n## 红黄绿规则\n");
        for (JsonNode r : root.path("triageRules")) {
            md.append("- **").append(r.path("level").asText()).append("** / ")
                    .append(r.path("category").asText()).append("：")
                    .append(join(r.path("keywords"))).append("\n");
        }
        md.append("\nCareLoop 使用「骨科通用 + 病种专属」统一随访表，疼痛为四档语义评级。\n");
        return md.toString();
    }

    public Map<String, Object> summary() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", root.path("id").asText());
        m.put("title", title());
        m.put("version", root.path("version").asText());
        m.put("specialty", specialty());
        m.put("source", source);
        m.put("diseaseId", activeDiseaseId);
        m.put("diseaseCount", diseases.size());
        m.put("nodeCount", root.path("followupNodes").size());
        m.put("domainCount", root.path("monitoringDomains").size());
        m.put("ruleCount", root.path("triageRules").size());
        m.put("diseases", listDiseases());
        return m;
    }

    public List<String> outline() {
        List<String> list = new ArrayList<>();
        list.add("一、骨科通用采集与红黄绿");
        list.add("二、病种专属采集包");
        list.add("三、统一术后随访日程（前7天每日，之后隔日+关键日）");
        list.add("四、疼痛四档与诊前简报");
        return list;
    }

    public record TriageHit(String level, String reason, String category,
                            String ruleId, String matchedKeyword, String suggestedAction) {
        public TriageHit(String level, String reason, String category) {
            this(level, reason, category, "", "", "");
        }
    }

    private void rebuildMergedRoot(String diseaseId) {
        this.activeDiseaseId = diseaseId == null ? activeDiseaseId : diseaseId;
        JsonNode pack = diseases.get(activeDiseaseId);
        ObjectNode merged = objectMapper.createObjectNode();
        merged.put("id", common.path("id").asText() + "+" + activeDiseaseId);
        merged.put("title", pack == null ? common.path("title").asText() : pack.path("title").asText());
        merged.put("version", common.path("version").asText() + "/"
                + (pack == null ? "-" : pack.path("version").asText()));
        merged.put("specialty", pack == null ? common.path("specialty").asText()
                : pack.path("specialty").asText(common.path("specialty").asText()));
        merged.put("disclaimer", common.path("disclaimer").asText());
        merged.put("careTips", pack != null && pack.hasNonNull("careTips")
                ? pack.path("careTips").asText() : common.path("careTips").asText());
        merged.set("monitoringDomains", common.path("monitoringDomains").deepCopy());
        merged.set("patientReplies", common.path("patientReplies").deepCopy());

        ArrayNode rules = merged.putArray("triageRules");
        appendAll(rules, common.path("triageRules"));
        if (pack != null) {
            appendAll(rules, pack.path("triageRules"));
        }

        ArrayNode asks = merged.putArray("briefingAskList");
        appendAll(asks, common.path("briefingAskList"));
        if (pack != null) {
            appendAll(asks, pack.path("briefingAskList"));
        }

        // 兼容旧 sync：用日程展开伪 followupNodes
        ArrayNode nodes = merged.putArray("followupNodes");
        for (Integer day : buildScheduleDays(14, pack)) {
            ObjectNode n = nodes.addObject();
            n.put("day", day);
            n.put("title", dayTitle(pack, day));
            n.put("question", "术后随访 D" + day);
        }
        this.root = merged;
    }

    private void appendAll(ArrayNode target, JsonNode src) {
        if (src == null || !src.isArray()) {
            return;
        }
        for (JsonNode n : src) {
            target.add(n.deepCopy());
        }
    }

    private String dayTitle(JsonNode pack, int day) {
        if (pack != null && pack.path("dayTitles").has(String.valueOf(day))) {
            return pack.path("dayTitles").path(String.valueOf(day)).asText("");
        }
        if (isExpandDay(pack, day)) {
            return "关键随访日";
        }
        return day <= 7 ? "日常随访" : "常规随访";
    }

    private boolean isExpandDay(JsonNode pack, int day) {
        if (pack == null) {
            return false;
        }
        for (JsonNode n : pack.path("expandDays")) {
            if (n.asInt() == day) {
                return true;
            }
        }
        return false;
    }

    private String matchedKeyword(String text, JsonNode keywords) {
        for (JsonNode k : keywords) {
            String kw = k.asText();
            if (kw != null && !kw.isBlank() && text.contains(kw.toLowerCase(Locale.ROOT))) {
                return kw;
            }
        }
        return null;
    }

    private String formatReason(JsonNode rule, String text) {
        String tpl = displayText(rule.path("reasonTemplate").asText("规则命中"));
        String category = rule.path("category").asText("");
        return tpl.replace("{category}", category) + " | 原文：" + text;
    }

    private String join(JsonNode arr) {
        List<String> parts = new ArrayList<>();
        for (JsonNode n : arr) {
            parts.add(n.asText());
        }
        return String.join("、", parts);
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }

    private String blankToDefault(String s, String def) {
        return s == null || s.isBlank() ? def : s;
    }

    public byte[] rawJsonBytes() {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(root)
                    .getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
