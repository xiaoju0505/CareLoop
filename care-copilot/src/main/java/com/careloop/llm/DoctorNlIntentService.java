package com.careloop.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 将医生自然语言解析为受限意图 JSON（非任意执行）。
 */
@Service
public class DoctorNlIntentService {

    private static final Logger log = LoggerFactory.getLogger(DoctorNlIntentService.class);

    private static final String SYSTEM = """
            你是 CareLoop 术后随访系统的医生助手意图解析器。
            只根据用户输入，输出一个 JSON 对象，不要 markdown，不要解释。
            字段：
            - intent: 枚举之一
              CANCEL_ALL_PLANS | CANCEL_PATIENT_PLAN | SHOW_DASHBOARD | QUERY_RECOVERY
              | HELP | CONFIRM | ABORT | UNKNOWN
            - patientId: 数字，可选，患者 ID
            - patientName: 字符串，可选，患者姓名
            - confidence: 0~1
            - replyHint: 给医生的一句话提示（中文）
            
            规则：
            1. 「取消所有/全部患者计划」「停掉全部随访」→ CANCEL_ALL_PLANS
            2. 「取消患者#12的计划」「停掉张三的随访」→ CANCEL_PATIENT_PLAN
            3. 「看板」「台账」「患者列表」→ SHOW_DASHBOARD
            4. 「查恢复」「XX最近怎么样」→ QUERY_RECOVERY（把姓名放 patientName）
            5. 「确认」「确认取消」「是的执行」→ CONFIRM
            6. 「放弃」「取消操作」「算了」→ ABORT
            7. 「你能做什么」「帮助」→ HELP
            8. 不确定 → UNKNOWN
            不要发明未列出的 intent。高危操作也先输出意图，由系统二次确认。
            """;

    private final DeepseekChatClient deepseek;
    private final ObjectMapper objectMapper;

    public DoctorNlIntentService(DeepseekChatClient deepseek, ObjectMapper objectMapper) {
        this.deepseek = deepseek;
        this.objectMapper = objectMapper;
    }

    public ObjectNode parse(String doctorText) {
        ObjectNode fallback = objectMapper.createObjectNode();
        fallback.put("intent", "UNKNOWN");
        fallback.put("confidence", 0);
        fallback.put("replyHint", "未能理解指令");

        if (doctorText == null || doctorText.isBlank()) {
            return fallback;
        }

        // 无 Key 时用规则兜底，保证「取消全部计划」仍可用
        ObjectNode ruleHit = ruleFallback(doctorText);
        if (ruleHit != null && !"UNKNOWN".equals(ruleHit.path("intent").asText())) {
            if (!deepseek.isReady()) {
                return ruleHit;
            }
        }

        if (!deepseek.isReady()) {
            return ruleHit != null ? ruleHit : fallback;
        }

        String raw = deepseek.chat(SYSTEM, doctorText.trim());
        if (raw == null || raw.isBlank()) {
            return ruleHit != null ? ruleHit : fallback;
        }
        try {
            String json = extractJson(raw);
            JsonNode node = objectMapper.readTree(json);
            ObjectNode out = objectMapper.createObjectNode();
            out.put("intent", node.path("intent").asText("UNKNOWN"));
            if (node.has("patientId") && node.get("patientId").canConvertToLong()) {
                out.put("patientId", node.get("patientId").asLong());
            }
            if (node.hasNonNull("patientName")) {
                out.put("patientName", node.path("patientName").asText(""));
            }
            out.put("confidence", node.path("confidence").asDouble(0.5));
            out.put("replyHint", node.path("replyHint").asText(""));
            return out;
        } catch (Exception e) {
            log.warn("解析 DeepSeek 意图失败: {} raw={}", e.getMessage(), raw);
            return ruleHit != null ? ruleHit : fallback;
        }
    }

    private ObjectNode ruleFallback(String text) {
        String t = text.replaceAll("\\s+", "");
        ObjectNode n = objectMapper.createObjectNode();
        n.put("confidence", 0.85);
        if (t.matches(".*(确认取消|确认执行|确认)$") || "确认".equals(t) || "是".equals(t) || "好的".equals(t)) {
            n.put("intent", "CONFIRM");
            n.put("replyHint", "确认待执行操作");
            return n;
        }
        if (t.contains("放弃") || t.contains("算了") || t.equals("取消操作")) {
            n.put("intent", "ABORT");
            n.put("replyHint", "放弃待执行操作");
            return n;
        }
        if ((t.contains("取消") || t.contains("停掉") || t.contains("停止"))
                && (t.contains("所有") || t.contains("全部"))
                && (t.contains("计划") || t.contains("随访"))) {
            n.put("intent", "CANCEL_ALL_PLANS");
            n.put("replyHint", "取消全部活跃随访计划");
            return n;
        }
        if ((t.contains("取消") || t.contains("停掉"))
                && (t.contains("计划") || t.contains("随访"))
                && (t.contains("#") || t.contains("患者"))) {
            n.put("intent", "CANCEL_PATIENT_PLAN");
            var m = java.util.regex.Pattern.compile("#?(\\d+)").matcher(text);
            if (m.find()) {
                n.put("patientId", Long.parseLong(m.group(1)));
            }
            n.put("replyHint", "取消指定患者计划");
            return n;
        }
        if (t.contains("看板") || t.contains("台账") || t.equals("患者列表")) {
            n.put("intent", "SHOW_DASHBOARD");
            n.put("replyHint", "展示看板");
            return n;
        }
        if (t.contains("帮助") || t.contains("你能做什么") || t.contains("指令")) {
            n.put("intent", "HELP");
            n.put("replyHint", "帮助");
            return n;
        }
        n.put("intent", "UNKNOWN");
        return n;
    }

    private static String extractJson(String raw) {
        String s = raw.trim();
        if (s.startsWith("```")) {
            s = s.replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "");
        }
        int a = s.indexOf('{');
        int b = s.lastIndexOf('}');
        if (a >= 0 && b > a) {
            return s.substring(a, b + 1);
        }
        return s;
    }
}
