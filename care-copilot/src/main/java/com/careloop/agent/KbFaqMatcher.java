package com.careloop.agent;

import com.careloop.ortho.OrthoKnowledgeService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 基于骨科规则库的可解释 FAQ（不调用外部大模型；对外不提「知识库」）。
 */
@Component
public class KbFaqMatcher {

    private final OrthoKnowledgeService knowledgeService;

    public KbFaqMatcher(OrthoKnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    public boolean looksLikeQuestion(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String t = text.trim();
        return t.contains("?") || t.contains("？") || t.contains("吗") || t.contains("嘛")
                || t.contains("能否") || t.contains("可不可以") || t.contains("能不能")
                || t.contains("怎么办") || t.contains("如何") || t.contains("什么")
                || t.startsWith("请问") || t.contains("建议");
    }

    public boolean canAnswer(String text) {
        return answer(text, null, null) != null;
    }

    public String answer(String question, String diagnosis, String caseNotes) {
        if (question == null || question.isBlank()) {
            return null;
        }
        String q = question.toLowerCase(Locale.ROOT);

        // 病历医嘱优先（仅限该患者 caseNotes，实现隔离）
        if (caseNotes != null && !caseNotes.isBlank()) {
            if (containsAny(q, "医嘱", "医生怎么说", "出院要求", "要注意什么")) {
                return "结合您的出院记录，请优先遵循：" + trim(caseNotes, 220)
                        + "。如与下述一般建议冲突，以您的主管医生医嘱为准。";
            }
        }

        if (containsAny(q, "洗澡", "淋浴", "泡澡", "泡脚")) {
            return "伤口未完全愈合前，建议避免浸泡式泡澡/泡脚，以免浸湿敷料增加感染风险。"
                    + "短时淋浴时注意保护伤口干燥，具体以您的出院医嘱为准。"
                    + "若敷料浸湿、渗液增多或发热，请及时告知我或就医。";
        }
        if (containsAny(q, "冰敷", "冷敷")) {
            return "术后早期可用冰敷减轻肿胀与疼痛，一般每次约15–20分钟，可间断进行，注意用毛巾隔开，避免冻伤皮肤。";
        }
        if (containsAny(q, "抬高", "垫高")) {
            return "休息时尽量抬高患肢高于心脏水平，有助于减轻肿胀。下地活动仍需助行器并有人陪同。";
        }
        if (containsAny(q, "抗凝", "血栓", "腿肿", "小腿")) {
            return "抗凝药物请按医嘱按时服用，不要自行停药。"
                    + "请对比双侧小腿：若出现单侧明显胀痛、发紫、皮温高，或胸闷气促，请立即联系医护或急诊。";
        }
        if (containsAny(q, "止痛", "疼", "痛", "镇痛")) {
            return "术后疼痛可用医嘱止痛药缓解。请用0–10分描述静息痛与活动痛。"
                    + "若剧痛难忍、突然加重或伴高热/肢体苍白麻木，请立即就医，不要只靠忍痛。";
        }
        if (containsAny(q, "发烧", "发热", "体温")) {
            return "请监测体温。低热需观察并告诉我具体度数；若≥38.5℃或伴寒战、伤口大量渗液/流脓，请尽快就医或急诊。";
        }
        if (containsAny(q, "伤口", "敷料", "渗液", "换药")) {
            return "请保持伤口清洁干燥，不要自行拆开敷料。"
                    + "若渗液增多、红肿热痛加重、有异味或裂开，请及时反馈；严重渗液/流脓需尽快就诊。";
        }
        if (containsAny(q, "走路", "下地", "锻炼", "屈膝", "踝泵")) {
            return "按计划渐进活动：可做踝泵，短距助行并有人陪同，避免独自冒险下地。"
                    + "活动后肿胀可有加重但应能逐渐缓解；若突然不能动或跌倒剧痛，请急诊。";
        }
        if (containsAny(q, "跌倒", "摔倒", "助行")) {
            return "防跌倒是硬规则：下地使用助行器、有人陪同，地面保持干燥。"
                    + "若已跌倒且剧痛/变形/意识改变，请立即急诊。";
        }
        if (containsAny(q, "复诊", "随访", "什么时候来")) {
            return "请按出院计划与医生约定时间复诊。院外我会按节点询问伤口、疼痛、用药与肿胀等情况；"
                    + "有不适也可随时告诉我。";
        }
        if (containsAny(q, "吃什么", "饮食", "喝酒", "抽烟")) {
            return "饮食以清淡营养、利于伤口愈合为宜；吸烟影响愈合与血栓风险，建议戒烟。"
                    + "饮酒及特殊饮食限制请遵医嘱，勿与抗凝药冲突。";
        }

        // 域信号兜底：匹配监测域名
        for (JsonNode d : knowledgeService.root().path("monitoringDomains")) {
            String name = d.path("name").asText("");
            String id = d.path("id").asText("");
            if (q.contains(name) || q.contains(id) || domainHit(q, id)) {
                return "关于「" + name + "」：" + d.path("why").asText()
                        + " 较平稳时常见：" + join(d.path("greenSignals"))
                        + "。若出现：" + join(d.path("yellowSignals"))
                        + "，请及时告诉我；若出现：" + join(d.path("redSignals"))
                        + "，请立即就医或急诊。";
            }
        }

        if (containsAny(q, "注意事项", "要注意", "怎么护理", "居家")) {
            String tips = knowledgeService.careTips();
            String prefix = (diagnosis == null || diagnosis.isBlank()) ? "" : "针对「" + diagnosis + "」，";
            return prefix + "居家请注意：" + tips
                    + (caseNotes == null || caseNotes.isBlank() ? "" : "\n您的病历备注：" + trim(caseNotes, 160));
        }

        return null;
    }

    private boolean domainHit(String q, String id) {
        return switch (id) {
            case "wound" -> containsAny(q, "伤口", "感染", "敷料");
            case "pain" -> containsAny(q, "疼", "痛", "睡眠");
            case "meds" -> containsAny(q, "药", "抗凝", "止痛");
            case "function" -> containsAny(q, "活动", "锻炼", "走路");
            case "vte" -> containsAny(q, "血栓", "气促", "胸闷");
            case "safety" -> containsAny(q, "跌倒", "安全");
            default -> false;
        };
    }

    private boolean containsAny(String q, String... keys) {
        for (String k : keys) {
            if (q.contains(k.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String join(JsonNode arr) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (JsonNode n : arr) {
            if (i++ > 0) {
                sb.append("、");
            }
            sb.append(n.asText());
            if (i >= 4) {
                break;
            }
        }
        return sb.toString();
    }

    private String trim(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
