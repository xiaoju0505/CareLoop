package com.careloop.ortho;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 骨科（膝关节置换术后）话术与随访模板。
 */
public final class OrthoCareTemplates {

    private OrthoCareTemplates() {
    }

    public static final String SPECIALTY = "骨科·关节置换";
    public static final String DEFAULT_DIAGNOSIS = "膝关节置换术后";

    public static void fillKneeReplacePlanNodes(ArrayNode nodes) {
        add(nodes, 1,
                "【骨科术后D1】今天伤口敷料有无渗湿？伤口周围是否红肿发热？"
                        + "静息疼痛评分（0-10分）？是否按医嘱抬高患肢、冰敷？");
        add(nodes, 3,
                "【骨科术后D3】止痛药/抗凝药是否按时服用？下地行走是否有人陪同？"
                        + "活动后膝关节肿胀是否加重？有无小腿胀痛或皮肤发紫？");
        add(nodes, 7,
                "【骨科术后D7】伤口愈合如何（干燥/渗液/裂开）？弯曲角度是否较前改善？"
                        + "能否借助助行器短距离行走？夜间疼痛是否影响睡眠？");
        add(nodes, 14,
                "【骨科复诊前D14】请用三句话告诉我："
                        + "①目前最大不适；②用药是否规律；③最希望医生今天重点检查什么。");
    }

    public static String welcomeMessage(String patientName, String diagnosis) {
        return "您好" + safe(patientName) + "，我是您的骨科术后随访助手。"
                + "已为您绑定「" + safe(diagnosis) + "」连续照护。"
                + "接下来我会按计划简单问几句伤口、疼痛和用药情况。"
                + "如出现明显渗液、发热、剧痛或小腿肿胀发紫，请立刻告诉我。";
    }

    public static String greenReply() {
        return "收到，目前情况比较平稳，请继续："
                + "①保持伤口清洁干燥；②按医嘱用药与功能锻炼；③避免跌倒。"
                + "若渗液增多、体温≥37.5℃或疼痛明显加重，随时找我。";
    }

    public static String yellowReply() {
        return "已记录您的不适，并通知骨科医护团队关注。"
                + "请先减少负重活动，抬高患肢休息；不要自行拆除敷料。"
                + "若症状在数小时内加重，请联系医院或提前复诊。";
    }

    public static String redReply() {
        return "您描述的情况需要尽快处理，我已紧急通知医护。"
                + "请保持电话畅通；如出现胸闷气促、大出血或意识改变，请立即拨打急救电话或就近急诊。";
    }

    public static String notifyPatientAfterDoctor() {
        return "骨科医护已查看您的反馈。请按医嘱休息观察，继续抬高患肢并按时用药。"
                + "若渗液、发热或疼痛加重，请立即联系医院或复诊。";
    }

    public static String briefingAskList() {
        return """
                - 伤口：是否仍渗液/红肿？敷料更换情况？
                - 疼痛：静息与活动评分？夜间是否痛醒？
                - 用药：止痛/抗凝是否规律？有无漏服？
                - 功能：助行距离、屈膝角度、是否需加强康复指导？
                - 血栓预警：小腿胀痛、皮肤发绀、呼吸困难？
                """;
    }

    public static void enrichPlanMeta(ObjectNode content, String diagnosis, String doctorName) {
        content.put("specialty", SPECIALTY);
        content.put("diagnosis", diagnosis == null || diagnosis.isBlank() ? DEFAULT_DIAGNOSIS : diagnosis);
        content.put("doctorName", doctorName == null ? "" : doctorName);
        content.put("careTips", "抬高患肢、伤口干燥、防跌倒、按医嘱抗凝/止痛、渐进功能锻炼");
    }

    private static void add(ArrayNode nodes, int day, String question) {
        ObjectNode n = nodes.addObject();
        n.put("day", day);
        n.put("question", question);
    }

    private static String safe(String s) {
        return s == null || s.isBlank() ? "" : s;
    }
}
