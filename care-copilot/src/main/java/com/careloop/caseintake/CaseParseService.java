package com.careloop.caseintake;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从电子病历/出院小结文本中抽取结构化字段（规则可解释，便于答辩）。
 */
@Service
public class CaseParseService {

    private static final Pattern PIPE = Pattern.compile(
            "^\\s*(?:建档|病例|入院|出院)?\\s*[|｜]\\s*(.+?)\\s*[|｜]\\s*(男|女)?\\s*[|｜]?\\s*(.+?)\\s*[|｜]\\s*(.+?)(?:\\s*[|｜]\\s*(.*))?\\s*$"
    );

    public Map<String, String> parse(String raw) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("rawText", raw == null ? "" : raw.trim());
        result.put("confidence", "low");

        if (raw == null || raw.isBlank()) {
            result.put("name", "待完善患者");
            result.put("diagnosis", "膝关节置换术后");
            result.put("doctorName", "主管医生");
            result.put("caseNotes", "");
            return result;
        }

        String text = raw.replace('\u00A0', ' ').trim();

        // 快捷格式：建档|张女士|女|膝关节置换术后|李医生|备注
        if (text.contains("|") || text.contains("｜")) {
            String[] parts = text.replace("｜", "|").split("\\|");
            int i = 0;
            if (parts.length > 0 && parts[0].matches(".*(建档|病例|出院小结|入院).*")) {
                i = 1;
            }
            if (parts.length - i >= 3) {
                result.put("name", clean(parts[i]));
                String g = clean(parts[i + 1]);
                if ("男".equals(g) || "女".equals(g)) {
                    result.put("gender", g);
                    result.put("diagnosis", clean(parts[i + 2]));
                    if (parts.length - i >= 4) {
                        result.put("doctorName", clean(parts[i + 3]));
                    }
                    if (parts.length - i >= 5) {
                        result.put("caseNotes", clean(parts[i + 4]));
                    }
                } else {
                    result.put("diagnosis", g);
                    result.put("doctorName", clean(parts[i + 2]));
                    if (parts.length - i >= 4) {
                        result.put("caseNotes", clean(joinFrom(parts, i + 3)));
                    }
                }
                result.put("confidence", "high");
                fillDefaults(result, text);
                return result;
            }
        }

        // 支持 姓名：刘秀英 / 姓名：【刘秀英】 / 姓名【刘秀英】
        putIfFound(result, "name", text,
                "(?:姓\\s*名|患者姓名|病人姓名)\\s*[:：]?\\s*[【\\[（(]*([\\u4e00-\\u9fa5A-Za-z·]{2,20})[】\\]）)]*",
                "(?:姓\\s*名|患者姓名|病人姓名)\\s*[【\\[]([\\u4e00-\\u9fa5A-Za-z·]{2,20})[】\\]]",
                "([\\u4e00-\\u9fa5]{2,4})(?:女士|先生|同志)");
        putIfFound(result, "gender", text,
                "(?:性\\s*别)\\s*[:：]?\\s*[【\\[（(]*(男|女)[】\\]）)]*",
                "性别[:：]?(男|女)");
        putIfFound(result, "phoneMask", text,
                "(?:联系电话|手机号|电话)\\s*[:：]\\s*(1\\d{10})",
                "(1\\d{2})\\d{4}(\\d{4})");
        putIfFound(result, "diagnosis", text,
                "(?:出院诊断|主要诊断|诊断名称|诊\\s*断)\\s*[:：]\\s*[【\\[（(]*([^\\n；;】\\]]{2,80})",
                "(膝关节置换|全膝置换|TKA|髋关节置换|骨折内固定|半月板)[^\\n]{0,40}");
        putIfFound(result, "doctorName", text,
                "(?:主刀医生|主治医师|主治医生|管床医生|医生)\\s*[:：]\\s*[【\\[（(]*([\\u4e00-\\u9fa5]{2,8})",
                "([\\u4e00-\\u9fa5]{2,4})(?:医生|主任|主任医师)");
        putIfFound(result, "dischargeAt", text,
                "(?:出院日期|出院时间|离院日期)\\s*[:：]\\s*(\\d{4}[-/.年]\\d{1,2}[-/.月]\\d{1,2}日?)",
                "(\\d{4}-\\d{2}-\\d{2})");

        Matcher age = Pattern.compile("(?:年\\s*龄)\\s*[:：]?\\s*[【\\[（(]*(\\d{1,3})").matcher(text);
        StringBuilder notes = new StringBuilder();
        if (age.find()) {
            notes.append("年龄：").append(age.group(1)).append("岁。");
        }
        Matcher op = Pattern.compile("(?:手术名称|术式)\\s*[:：]\\s*([^\\n]{2,80})").matcher(text);
        if (op.find()) {
            notes.append("术式：").append(clean(op.group(1))).append("。");
        }
        Matcher tip = Pattern.compile("(?:出院医嘱|医嘱|注意事项)\\s*[:：]\\s*([^\\n]{2,200})").matcher(text);
        if (tip.find()) {
            notes.append("医嘱：").append(clean(tip.group(1))).append("。");
        }
        if (notes.isEmpty()) {
            notes.append(text.length() > 500 ? text.substring(0, 500) + "…" : text);
        }
        result.put("caseNotes", notes.toString());

        int hits = 0;
        for (String k : new String[]{"name", "diagnosis", "doctorName", "gender"}) {
            if (result.get(k) != null && !result.get(k).isBlank()) {
                hits++;
            }
        }
        result.put("confidence", hits >= 3 ? "high" : hits >= 2 ? "medium" : "low");
        fillDefaults(result, text);
        return result;
    }

    private void fillDefaults(Map<String, String> result, String text) {
        if (blank(result.get("name")) || "待完善患者".equals(result.get("name"))) {
            String recovered = recoverName(text);
            if (recovered != null) {
                result.put("name", recovered);
            } else if (blank(result.get("name"))) {
                result.put("name", "待完善患者");
            }
        }
        if (blank(result.get("diagnosis"))) {
            if (text.contains("膝") || text.contains("TKA") || text.contains("置换")) {
                result.put("diagnosis", "膝关节置换术后");
            } else {
                result.put("diagnosis", "骨科术后随访");
            }
        }
        if (blank(result.get("doctorName"))) {
            result.put("doctorName", "主管医生");
        }
        if (blank(result.get("caseNotes"))) {
            result.put("caseNotes", text.length() > 800 ? text.substring(0, 800) + "…" : text);
        }
        String phone = result.get("phoneMask");
        if (phone != null && phone.matches("1\\d{10}")) {
            result.put("phoneMask", phone.substring(0, 3) + "****" + phone.substring(7));
        }
        String d = result.get("dischargeAt");
        if (d != null) {
            result.put("dischargeAt", d.replace("年", "-").replace("月", "-").replace("日", "")
                    .replace(".", "-").replace("/", "-"));
        }
    }

    /** 病历摘要里已有「姓名【刘秀英】」时回填到结构化姓名。 */
    private String recoverName(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = Pattern.compile(
                "(?:姓\\s*名|患者姓名|病人姓名)\\s*[:：]?\\s*[【\\[（(]*([\\u4e00-\\u9fa5A-Za-z·]{2,20})[】\\]）)]*"
        ).matcher(text);
        if (m.find()) {
            String n = clean(m.group(1));
            if (!n.isBlank() && !"待完善患者".equals(n)) {
                return n;
            }
        }
        return null;
    }

    private void putIfFound(Map<String, String> map, String key, String text, String... patterns) {
        if (!blank(map.get(key))) {
            return;
        }
        for (String p : patterns) {
            Matcher m = Pattern.compile(p).matcher(text);
            if (m.find()) {
                String v = m.group(1);
                if (v != null && !v.isBlank()) {
                    map.put(key, clean(v));
                    return;
                }
            }
        }
    }

    private String joinFrom(String[] parts, int start) {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < parts.length; i++) {
            if (i > start) {
                sb.append("|");
            }
            sb.append(parts[i].trim());
        }
        return sb.toString();
    }

    private String clean(String s) {
        if (s == null) {
            return "";
        }
        return s.replaceAll("[\\s　]+", " ")
                .replaceAll("^[：:\\-—【\\[（(]+", "")
                .replaceAll("[】\\]）)]+$", "")
                .trim();
    }

    private boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
