package com.careloop.collect;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从患者自由文本抽取结构化随访字段（疼痛评分、伤口、用药依从等）。
 */
@Service
public class StructuredCollectService {

    private static final Pattern PAIN = Pattern.compile(
            "(?:疼痛|疼|痛|静息痛|疼痛评分)?\\s*(?:是|为|到|打|评)?\\s*([0-9]|10)\\s*(?:分|／|/|点)?"
                    + "|(?:疼到|痛到)\\s*([0-9]|10)\\s*分?"
                    + "|([0-9]|10)\\s*分(?:疼痛|痛|疼)?"
    );

    private final ObjectMapper objectMapper;

    public StructuredCollectService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> extract(String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            return m;
        }
        String t = text.trim();

        Integer pain = parsePain(t);
        if (pain != null) {
            m.put("painScore", pain);
        }

        String wound = parseWound(t);
        if (wound != null) {
            m.put("woundStatus", wound);
        }

        String med = parseMed(t);
        if (med != null) {
            m.put("medAdherence", med);
        }

        if (containsAny(t, "气短", "气促", "喘不上", "呼吸困难", "胸闷")) {
            m.put("dyspnea", true);
        }
        if (containsAny(t, "发热", "发烧", "低热", "高烧") || t.matches(".*3[7-9]\\.\\d.*")) {
            m.put("feverMention", true);
        }
        if (containsAny(t, "渗液", "渗湿", "流脓", "敷料湿")) {
            m.put("oozing", true);
        }
        if (containsAny(t, "红肿", "发红", "肿热")) {
            m.put("redness", true);
        }
        return m;
    }

    public ObjectNode toJson(Map<String, Object> fields) {
        return objectMapper.valueToTree(fields);
    }

    private Integer parsePain(String t) {
        // 四档语义 → 便于趋势序列的近似分
        if (t.contains("剧痛")) {
            return 8;
        }
        if (t.contains("中度疼痛") || t.contains("中度痛")) {
            return 5;
        }
        if (t.contains("轻微疼痛") || t.contains("轻微痛") || t.contains("轻度疼痛")) {
            return 2;
        }
        if (t.contains("无疼痛") || t.contains("不疼") || t.contains("不痛")) {
            return 0;
        }
        Matcher m = PAIN.matcher(t.replace(" ", ""));
        if (m.find()) {
            for (int i = 1; i <= m.groupCount(); i++) {
                if (m.group(i) != null) {
                    int v = Integer.parseInt(m.group(i));
                    if (v >= 0 && v <= 10) {
                        return v;
                    }
                }
            }
        }
        if (t.contains("痛得厉害") || t.contains("疼得厉害")) {
            return 8;
        }
        return null;
    }

    private String parseWound(String t) {
        if (containsAny(t, "流脓", "大量渗液", "伤口裂开很大")) {
            return "SEVERE";
        }
        if (containsAny(t, "渗液", "渗湿", "敷料湿", "换药还湿")) {
            return "OOZING";
        }
        if (containsAny(t, "红肿", "发红发热", "伤口红")) {
            return "RED_SWOLLEN";
        }
        if (containsAny(t, "干燥", "干爽", "敷料干", "伤口干")) {
            return "DRY";
        }
        return null;
    }

    private String parseMed(String t) {
        if (containsAny(t, "没吃药", "忘记吃药", "漏服", "自行停药", "停抗凝", "自己减药")) {
            return "MISS";
        }
        if (containsAny(t, "按时吃药", "按时服药", "已吃药", "药吃了", "按医嘱")) {
            return "OK";
        }
        if (containsAny(t, "偶尔忘", "有时忘", "差不多吃")) {
            return "PARTIAL";
        }
        return null;
    }

    private boolean containsAny(String t, String... keys) {
        for (String k : keys) {
            if (t.contains(k)) {
                return true;
            }
        }
        return false;
    }
}
