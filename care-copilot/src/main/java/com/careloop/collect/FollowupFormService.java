package com.careloop.collect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一术后随访表单：由知识库 formItems 驱动（通用 A + 病种 B + 医生加项 C）。
 */
@Service
public class FollowupFormService {

    public static final List<String> PAIN_OPTIONS = List.of("无疼痛", "轻微疼痛", "中度疼痛", "剧痛");

    private final ObjectMapper objectMapper;

    public FollowupFormService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ObjectNode buildFollowupForm(JsonNode common,
                                        JsonNode disease,
                                        int day,
                                        String dayTitle,
                                        List<String> doctorExtras,
                                        JsonNode modulesOverride) {
        return buildFollowupForm(common, disease, day, dayTitle, doctorExtras, modulesOverride, false);
    }

    public ObjectNode buildFollowupForm(JsonNode common,
                                        JsonNode disease,
                                        int day,
                                        String dayTitle,
                                        List<String> doctorExtras,
                                        JsonNode modulesOverride,
                                        boolean shortMode) {
        String diseaseTitle = disease == null ? "骨科术后"
                : disease.path("title").asText("骨科术后");
        String title = "术后随访 · D" + Math.max(day, 1)
                + (dayTitle == null || dayTitle.isBlank() ? "" : " · " + dayTitle)
                + (shortMode ? "（简表）" : "");
        ObjectNode form = baseForm(title, shortMode
                ? "近几日平稳，今日为简表，约1分钟完成"
                : "请逐项选择（骨科通用 + " + diseaseTitle + "），约2分钟");
        form.put("layer", "unified");
        form.put("diseaseId", disease == null ? "" : disease.path("id").asText(""));
        form.put("dayOffset", day);
        form.put("shortMode", shortMode);
        form.put("firstDay", day <= 1);
        ArrayNode items = form.putArray("items");

        JsonNode modules = mergeModules(common, disease, modulesOverride);
        boolean expandDay = isExpandDay(disease, day);
        List<String> shortIds = shortItemIds(common);

        if (common != null && common.path("formItems").isArray() && !common.path("formItems").isEmpty()) {
            for (JsonNode raw : common.path("formItems")) {
                if (!includeCommonItem(raw, modules, shortMode, shortIds)) {
                    continue;
                }
                items.add(itemFromProtocol(raw, day <= 1));
            }
        } else {
            appendLegacyCommon(items, modules, day <= 1);
        }

        if (!shortMode && disease != null && disease.path("specialtyItems").isArray()) {
            boolean any = false;
            for (JsonNode raw : disease.path("specialtyItems")) {
                String freq = raw.path("frequency").asText("routine");
                if ("expand".equalsIgnoreCase(freq) && !expandDay && day > 7) {
                    continue;
                }
                if ("expand".equalsIgnoreCase(freq) && !expandDay && day > 1 && day < 7 && !"routine".equals(freq)) {
                    // 非关键日的 expand 题：前7天仍可出 routine；expand 仅关键日
                }
                if ("expand".equalsIgnoreCase(freq) && !expandDay) {
                    continue;
                }
                if (!any) {
                    items.add(sectionLabel("disease_specific", "二、" + diseaseTitle + "专属"));
                    any = true;
                }
                items.add(itemFromProtocol(raw, false));
            }
        }

        if (!shortMode && doctorExtras != null && !doctorExtras.isEmpty()) {
            items.add(sectionLabel("doctor_extra", "三、医生加项"));
            int i = 1;
            for (String extra : doctorExtras) {
                if (extra == null || extra.isBlank()) {
                    continue;
                }
                items.add(customOnly("doctor_extra_" + i, "医生关心：" + extra.trim()));
                i++;
            }
        }

        ArrayNode skip = form.putArray("skipReasons");
        if (common != null && common.path("skipReasons").isArray()) {
            for (JsonNode s : common.path("skipReasons")) {
                skip.add(s.asText());
            }
        } else {
            skip.add("太累/疼痛无法填写");
            skip.add("外出不在家");
            skip.add("已住院/急诊");
            skip.add("其他");
        }
        return form;
    }

    private boolean includeCommonItem(JsonNode raw, JsonNode modules, boolean shortMode, List<String> shortIds) {
        String type = raw.path("type").asText("choice");
        String id = raw.path("id").asText();
        if ("section".equals(type)) {
            return !shortMode;
        }
        String mod = raw.path("module").asText("");
        if (!mod.isBlank() && !flag(modules, mod, !"anticoagulant".equals(mod))) {
            return false;
        }
        if (shortMode) {
            return shortIds.contains(id) || raw.path("shortFormInclude").asBoolean(false);
        }
        return true;
    }

    private List<String> shortItemIds(JsonNode common) {
        List<String> ids = new ArrayList<>();
        if (common != null && common.path("shortForm").path("itemIds").isArray()) {
            for (JsonNode n : common.path("shortForm").path("itemIds")) {
                ids.add(n.asText());
            }
        }
        if (ids.isEmpty()) {
            ids.addAll(List.of("respondent", "trend", "redflag", "note"));
        }
        return ids;
    }

    private boolean isExpandDay(JsonNode disease, int day) {
        if (disease == null) {
            return day == 1 || day == 7 || day == 14;
        }
        for (JsonNode n : disease.path("expandDays")) {
            if (n.asInt() == day) {
                return true;
            }
        }
        return false;
    }

    private ObjectNode itemFromProtocol(JsonNode raw, boolean firstDay) {
        String type = raw.path("type").asText("choice");
        String id = raw.path("id").asText("item");
        if ("section".equals(type)) {
            return sectionLabel(id, raw.path("label").asText(""));
        }
        if ("text".equals(type)) {
            ObjectNode item = customOnly(id, raw.path("label").asText(id));
            copyShowIf(raw, item);
            item.put("shortFormInclude", raw.path("shortFormInclude").asBoolean(false));
            item.put("required", raw.path("required").asBoolean(false));
            return item;
        }
        String label = firstDay && raw.hasNonNull("labelFirstDay")
                ? raw.path("labelFirstDay").asText()
                : raw.path("label").asText(id);
        List<String> opts = new ArrayList<>();
        JsonNode optNode = firstDay && raw.has("optionsFirstDay")
                ? raw.path("optionsFirstDay") : raw.path("options");
        for (JsonNode o : optNode) {
            opts.add(o.asText());
        }
        boolean multi = raw.path("multi").asBoolean(false);
        boolean allowCustom = !raw.has("allowCustom") || raw.path("allowCustom").asBoolean(true);
        ObjectNode item = choice(id, label, multi, opts, allowCustom, raw.path("customHint").asText("补充描述"));
        if (raw.path("exclusive").asBoolean(false)) {
            item.put("exclusive", true);
            item.put("multi", false);
        }
        copyShowIf(raw, item);
        if (raw.has("signalMap")) {
            item.set("signalMap", raw.path("signalMap").deepCopy());
        }
        item.put("shortFormInclude", raw.path("shortFormInclude").asBoolean(false));
        boolean required = raw.has("required")
                ? raw.path("required").asBoolean(false)
                : !"text".equals(type);
        item.put("required", required);
        return item;
    }

    private void copyShowIf(JsonNode raw, ObjectNode item) {
        if (raw.has("showIf") && raw.path("showIf").isObject()) {
            item.set("showIf", raw.path("showIf").deepCopy());
        }
    }

    private void appendLegacyCommon(ArrayNode items, JsonNode modules, boolean firstDay) {
        items.add(sectionLabel("ortho_common", "一、骨科通用情况"));
        items.add(choice("respondent", "本次由谁填写", false, List.of("患者本人", "家属代答"), false, ""));
        items.add(choice("trend", firstDay ? "出院以来整体感觉" : "相对昨天", false,
                firstDay ? List.of("逐渐好转", "差不多", "变差") : List.of("更好", "差不多", "变差"),
                true, "变化补充"));
        items.add(choice("temp", "今天最高体温", false,
                List.of("今天没量", "低于37.5℃", "37.5-38.0℃", "38.1-38.4℃", "≥38.5℃"), true, "体温补充"));
        items.add(choice("pain", "疼痛程度", false, PAIN_OPTIONS, true, "疼痛补充"));
        ObjectNode sleep = choice("sleep_pain", "是否因疼痛影响睡眠", false,
                List.of("否", "是（曾痛醒或难入睡）"), true, "睡眠补充");
        ObjectNode showIf = objectMapper.createObjectNode();
        ArrayNode painVals = showIf.putArray("pain");
        painVals.add("中度疼痛");
        painVals.add("剧痛");
        sleep.set("showIf", showIf);
        items.add(sleep);
        if (flag(modules, "wound", true)) {
            items.add(choice("wound", "伤口/切口情况", false,
                    List.of("干燥", "少量渗液", "渗湿加重/红肿", "裂开或异味/流脓"), true, "伤口补充"));
        }
        if (flag(modules, "meds", true)) {
            items.add(choice("analgesic", "止痛药", false,
                    List.of("已按时", "偶有漏服", "自行减量/停药", "医嘱未开/不需要", "不清楚"), true, "止痛补充"));
        }
        if (flag(modules, "anticoagulant", false)) {
            items.add(choice("anticoagulant", "抗凝药", false,
                    List.of("已按时", "偶有漏服", "自行减量/停药", "医嘱未开抗凝", "不清楚"), true, "抗凝补充"));
        }
        ObjectNode red = choice("redflag", "急症相关", false,
                List.of("无以上情况", "发热感", "单侧肢体明显胀痛/发紫", "气短/胸闷", "感觉或活动突然变差"),
                true, "其他急症");
        red.put("exclusive", true);
        items.add(red);
        if (flag(modules, "safety", true)) {
            items.add(choice("safety", "防跌倒与安全", false,
                    List.of("有保护下地/休息为主", "偶有独自短距", "曾差点跌倒", "已跌倒"), true, "安全补充"));
        }
        items.add(customOnly("note", "今日最想告诉医生的一句话"));
    }

    public ObjectNode dailyForm() {
        return buildFollowupForm(null, null, 1, "随访", List.of(), null, false);
    }

    public ObjectNode formFromNode(JsonNode node) {
        if (node != null && node.has("form") && node.path("form").has("items")) {
            return (ObjectNode) node.path("form").deepCopy();
        }
        List<String> focus = new ArrayList<>();
        if (node != null) {
            for (JsonNode f : node.path("focus")) {
                focus.add(f.asText());
            }
        }
        String title = node == null ? "随访采集" : node.path("title").asText("随访采集");
        return formFromFocus(focus, title);
    }

    public ObjectNode formFromFocus(List<String> focus, String title) {
        ObjectNode form = baseForm(title, "请逐项选择题作答，必要时补充自定义描述");
        ArrayNode items = form.putArray("items");
        if (focus == null || focus.isEmpty()) {
            focus = List.of("wound", "pain", "meds");
        }
        for (String f : focus) {
            switch (f == null ? "" : f.toLowerCase()) {
                case "wound" -> items.add(choice("wound", "伤口情况", false,
                        List.of("干燥", "少量渗液", "渗湿加重/红肿", "裂开/异味/流脓"), true, "伤口补充"));
                case "pain" -> items.add(choice("pain", "疼痛程度", false, PAIN_OPTIONS, true, "疼痛补充"));
                case "meds" -> {
                    items.add(choice("analgesic", "止痛药", false,
                            List.of("按时服用", "偶有漏服", "自行停药/减量", "不清楚"), true, "补充"));
                    items.add(choice("anticoagulant", "抗凝药", false,
                            List.of("按时服用", "偶有漏服", "自行停药/减量", "未开抗凝"), true, "补充"));
                }
                case "function" -> items.add(choice("function", "功能活动", false,
                        List.of("较前改善", "差不多", "因痛不敢活动", "明显倒退"), true, "活动补充"));
                case "vte" -> items.add(choice("vte", "肿胀/血栓相关", false,
                        List.of("双侧对称、无异样", "肿胀但可接受", "单侧肢体胀痛/皮温高", "气促/胸痛"), true, "补充"));
                case "safety" -> items.add(choice("safety", "防跌倒与安全", false,
                        List.of("有人陪同+助行器", "偶有独自短距", "曾差点跌倒", "已跌倒"), true, "安全补充"));
                default -> {
                }
            }
        }
        items.add(customOnly("note", "其他想告诉医生的情况"));
        return form;
    }

    /** 从答卷文本提取选项级最高信号；form 为空时走关键词兜底 */
    public String highestSignalFromAnswer(String answerText, JsonNode form) {
        if (answerText == null || answerText.isBlank()) {
            return "GREEN";
        }
        int rank = 0;
        String level = "GREEN";
        if (form != null) {
            for (JsonNode item : form.path("items")) {
                JsonNode map = item.path("signalMap");
                if (!map.isObject()) {
                    continue;
                }
                Iterator<String> it = map.fieldNames();
                while (it.hasNext()) {
                    String opt = it.next();
                    if (answerText.contains(opt)) {
                        String lv = map.path(opt).asText("GREEN");
                        int r = rankOf(lv);
                        if (r > rank) {
                            rank = r;
                            level = lv;
                        }
                    }
                }
            }
        }
        if (rank == 0) {
            if (answerText.contains("气短") || answerText.contains("胸闷") || answerText.contains("裂开或异味")
                    || answerText.contains("流脓") || answerText.contains("已跌倒")
                    || answerText.contains("≥38.5")) {
                return "RED";
            }
            if (answerText.contains("剧痛") || answerText.contains("变差") || answerText.contains("渗湿")
                    || answerText.contains("发紫") || answerText.contains("漏服") || answerText.contains("自行")
                    || answerText.contains("中度疼痛") || answerText.contains("差点跌倒")) {
                return "YELLOW";
            }
        }
        return level;
    }

    private int rankOf(String level) {
        return switch (level == null ? "" : level.toUpperCase()) {
            case "RED" -> 3;
            case "YELLOW" -> 2;
            default -> 1;
        };
    }

    public String formatForFeishu(JsonNode form) {
        if (form == null || !form.has("items")) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("采集方式：统一术后随访（可简表/分支）\n");
        int i = 1;
        for (JsonNode item : form.path("items")) {
            if ("section".equals(item.path("type").asText())) {
                sb.append("\n").append(item.path("label").asText()).append("\n");
                continue;
            }
            sb.append(i++).append(") ").append(item.path("label").asText());
            if (item.path("options").isArray() && !item.path("options").isEmpty()) {
                List<String> opts = new ArrayList<>();
                for (JsonNode o : item.path("options")) {
                    opts.add(o.asText());
                }
                sb.append("　").append(String.join(" / ", opts));
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    public String summarizeQuestion(String title, JsonNode form) {
        StringBuilder sb = new StringBuilder();
        if (title != null && !title.isBlank()) {
            sb.append(title).append("\n");
        }
        sb.append("请按下列项目选择题作答，并可补充描述：");
        if (form != null) {
            List<String> labels = new ArrayList<>();
            for (JsonNode item : form.path("items")) {
                if ("section".equals(item.path("type").asText())) {
                    continue;
                }
                labels.add(item.path("label").asText());
            }
            if (!labels.isEmpty()) {
                sb.append(String.join("、", labels));
            }
        }
        return sb.toString();
    }

    public Map<String, Object> toMap(JsonNode form) {
        if (form == null || form.isMissingNode() || form.isNull()) {
            return Map.of();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = objectMapper.convertValue(form, Map.class);
            return m == null ? Map.of() : m;
        } catch (Exception e) {
            return Map.of();
        }
    }

    public String toJson(JsonNode form) {
        try {
            return objectMapper.writeValueAsString(form);
        } catch (Exception e) {
            return "{}";
        }
    }

    public JsonNode parseFormJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    public String composeAnswer(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return "";
        }
        if (payload.get("skipped") != null && Boolean.parseBoolean(String.valueOf(payload.get("skipped")))) {
            String reason = payload.get("skipReason") == null ? "未说明" : String.valueOf(payload.get("skipReason"));
            return "【今日跳过】原因：" + reason;
        }
        StringBuilder sb = new StringBuilder();
        Object selections = payload.get("selections");
        if (selections instanceof List<?> list) {
            for (Object row : list) {
                if (!(row instanceof Map<?, ?> m)) {
                    continue;
                }
                String label = m.get("label") == null ? "" : String.valueOf(m.get("label"));
                Object chosen = m.get("chosen");
                String custom = m.get("custom") == null ? "" : String.valueOf(m.get("custom")).trim();
                if (chosen instanceof List<?> opts) {
                    List<String> parts = new ArrayList<>();
                    for (Object o : opts) {
                        parts.add(String.valueOf(o));
                    }
                    sb.append(label).append("：").append(String.join("、", parts));
                } else if (chosen != null && !String.valueOf(chosen).isBlank()) {
                    sb.append(label).append("：").append(chosen);
                } else if (!custom.isBlank()) {
                    sb.append(label).append("：");
                } else {
                    continue;
                }
                if (!custom.isBlank()) {
                    sb.append("（补充：").append(custom).append("）");
                }
                sb.append("；");
            }
        }
        Object note = payload.get("note");
        if (note != null && !String.valueOf(note).isBlank()) {
            sb.append("补充说明：").append(note);
        }
        String text = sb.toString().trim();
        if (text.endsWith("；")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    /**
     * 校验必填：可见的 choice 默认必答；text 默认选填。
     * @return 缺失项中文标签列表，空表示通过
     */
    public List<String> missingRequiredLabels(JsonNode form, Map<String, Object> payload) {
        List<String> missing = new ArrayList<>();
        if (form == null || payload == null) {
            return missing;
        }
        Map<String, List<String>> chosenById = new LinkedHashMap<>();
        Map<String, String> customById = new LinkedHashMap<>();
        Object selections = payload.get("selections");
        if (selections instanceof List<?> list) {
            for (Object row : list) {
                if (!(row instanceof Map<?, ?> m)) {
                    continue;
                }
                String id = m.get("id") == null ? "" : String.valueOf(m.get("id"));
                List<String> chosen = new ArrayList<>();
                Object c = m.get("chosen");
                if (c instanceof List<?> opts) {
                    for (Object o : opts) {
                        chosen.add(String.valueOf(o));
                    }
                } else if (c != null && !String.valueOf(c).isBlank()) {
                    chosen.add(String.valueOf(c));
                }
                chosenById.put(id, chosen);
                String custom = m.get("custom") == null ? "" : String.valueOf(m.get("custom")).trim();
                customById.put(id, custom);
            }
        }
        for (JsonNode item : form.path("items")) {
            String type = item.path("type").asText("choice");
            if ("section".equals(type)) {
                continue;
            }
            if (!showIfSatisfied(item, chosenById)) {
                continue;
            }
            boolean required = item.path("required").asBoolean(!"text".equals(type));
            if (!required) {
                continue;
            }
            String id = item.path("id").asText();
            List<String> chosen = chosenById.getOrDefault(id, List.of());
            String custom = customById.getOrDefault(id, "");
            if (chosen.isEmpty() && custom.isBlank()) {
                missing.add(item.path("label").asText(id));
            }
        }
        return missing;
    }

    private boolean showIfSatisfied(JsonNode item, Map<String, List<String>> chosenById) {
        JsonNode showIf = item.path("showIf");
        if (!showIf.isObject() || showIf.isEmpty()) {
            return true;
        }
        Iterator<String> it = showIf.fieldNames();
        while (it.hasNext()) {
            String key = it.next();
            List<String> want = new ArrayList<>();
            for (JsonNode v : showIf.path(key)) {
                want.add(v.asText());
            }
            List<String> chosen = chosenById.getOrDefault(key, List.of());
            boolean hit = false;
            for (String w : want) {
                if (chosen.contains(w)) {
                    hit = true;
                    break;
                }
            }
            if (!hit) {
                return false;
            }
        }
        return true;
    }

    private JsonNode mergeModules(JsonNode common, JsonNode disease, JsonNode override) {
        ObjectNode m = objectMapper.createObjectNode();
        copyBools(m, common == null ? null : common.path("modules"));
        copyBools(m, disease == null ? null : disease.path("modules"));
        copyBools(m, override);
        return m;
    }

    private void copyBools(ObjectNode target, JsonNode src) {
        if (src == null || !src.isObject()) {
            return;
        }
        src.fields().forEachRemaining(e -> {
            if (e.getValue().isBoolean()) {
                target.put(e.getKey(), e.getValue().asBoolean());
            }
        });
    }

    private boolean flag(JsonNode modules, String key, boolean def) {
        if (modules == null || !modules.has(key)) {
            return def;
        }
        return modules.path(key).asBoolean(def);
    }

    private ObjectNode baseForm(String title, String prompt) {
        ObjectNode form = objectMapper.createObjectNode();
        form.put("version", 3);
        form.put("title", title == null ? "术后随访" : title);
        form.put("prompt", prompt);
        form.put("mode", "choice_plus_custom");
        return form;
    }

    private ObjectNode sectionLabel(String id, String label) {
        ObjectNode item = objectMapper.createObjectNode();
        item.put("id", id);
        item.put("label", label);
        item.put("type", "section");
        item.put("multi", false);
        item.put("allowCustom", false);
        item.putArray("options");
        return item;
    }

    private ObjectNode choice(String id, String label, boolean multi, List<String> options,
                              boolean allowCustom, String customHint) {
        ObjectNode item = objectMapper.createObjectNode();
        item.put("id", id);
        item.put("label", label);
        item.put("type", "choice");
        item.put("multi", multi);
        item.put("allowCustom", allowCustom);
        item.put("customHint", customHint == null ? "补充描述" : customHint);
        item.put("required", true);
        ArrayNode opts = item.putArray("options");
        for (String o : options) {
            opts.add(o);
        }
        return item;
    }

    private ObjectNode customOnly(String id, String label) {
        ObjectNode item = objectMapper.createObjectNode();
        item.put("id", id);
        item.put("label", label);
        item.put("type", "text");
        item.put("multi", false);
        item.put("allowCustom", true);
        item.put("customHint", "选填");
        item.put("required", false);
        item.putArray("options");
        return item;
    }
}
