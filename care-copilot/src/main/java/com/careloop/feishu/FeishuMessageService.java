package com.careloop.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class FeishuMessageService {

    private static final Logger log = LoggerFactory.getLogger(FeishuMessageService.class);
    private static final String SEND_URL =
            "https://open.feishu.cn/open-apis/im/v1/messages?receive_id_type={receiveIdType}";

    private final FeishuTokenService tokenService;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public FeishuMessageService(FeishuTokenService tokenService, ObjectMapper objectMapper) {
        this.tokenService = tokenService;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.create();
    }

    public String sendAlertCard(String receiveIdType, String receiveId,
                                String patientName, String level, String reason, Long alertId) {
        return sendAlertCard(receiveIdType, receiveId, patientName, level, reason, alertId,
                0L, "", "", "", reason);
    }

    public String sendAlertCard(String receiveIdType, String receiveId,
                                String patientName, String level, String reason, Long alertId,
                                long planId, String ruleId, String matchedKeyword,
                                String suggestedAction, String evidenceSummary) {
        return sendInteractive(receiveIdType, receiveId,
                buildAlertCard(patientName, level, reason, alertId, planId,
                        ruleId, matchedKeyword, suggestedAction, evidenceSummary));
    }

    public String sendConsultCard(String receiveIdType, String receiveId,
                                  String patientName, long patientId, String diagnosis,
                                  String question, Long alertId) {
        return sendInteractive(receiveIdType, receiveId,
                buildConsultCard(patientName, patientId, diagnosis, question, alertId));
    }

    public String sendCarePlanConfirmCard(String receiveIdType, String receiveId,
                                          String patientName, String diagnosis, Long planId,
                                          ObjectNode content) {
        return sendCarePlanConfirmCard(receiveIdType, receiveId, patientName, diagnosis, planId, content, null);
    }

    public String sendCarePlanConfirmCard(String receiveIdType, String receiveId,
                                          String patientName, String diagnosis, Long planId,
                                          ObjectNode content, String editUrl) {
        // editUrl 保留兼容，但卡片主按钮改为飞书内编辑，不再跳 H5
        return sendInteractive(receiveIdType, receiveId,
                buildCarePlanCard(patientName, diagnosis, planId, content));
    }

    public String sendBriefingCard(String receiveIdType, String receiveId,
                                   String patientName, String risk, String contentMd, Long briefingId) {
        return sendBriefingCard(receiveIdType, receiveId, patientName, risk, contentMd, briefingId, 0L, 0L);
    }

    public String sendBriefingCard(String receiveIdType, String receiveId,
                                   String patientName, String risk, String contentMd, Long briefingId,
                                   long patientId, long planId) {
        return sendInteractive(receiveIdType, receiveId,
                buildBriefingCard(patientName, risk, contentMd, briefingId, patientId, planId));
    }

    public String sendInteractive(String receiveIdType, String receiveId, ObjectNode card) {
        try {
            String token = tokenService.getTenantAccessToken();
            String contentJson = objectMapper.writeValueAsString(card);

            Map<String, Object> payload = Map.of(
                    "receive_id", receiveId,
                    "msg_type", "interactive",
                    "content", contentJson
            );

            String body = restClient.post()
                    .uri(SEND_URL, receiveIdType)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + token)
                    .body(payload)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(body);
            if (root.path("code").asInt(-1) != 0) {
                throw new IllegalStateException("发送飞书消息失败: " + body);
            }
            String messageId = root.path("data").path("message_id").asText();
            log.info("飞书卡片已发送 messageId={}", messageId);
            return messageId;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("发送飞书卡片异常", e);
        }
    }

    public String sendImage(String receiveIdType, String receiveId, String imageKey) {
        return sendRaw(receiveIdType, receiveId, "image",
                objectMapper.createObjectNode().put("image_key", imageKey).toString());
    }

    public String sendText(String receiveIdType, String receiveId, String text) {
        return sendRaw(receiveIdType, receiveId, "text",
                objectMapper.createObjectNode().put("text", text).toString());
    }

    private String sendRaw(String receiveIdType, String receiveId, String msgType, String contentJson) {
        try {
            String token = tokenService.getTenantAccessToken();
            Map<String, Object> payload = Map.of(
                    "receive_id", receiveId,
                    "msg_type", msgType,
                    "content", contentJson
            );
            String body = restClient.post()
                    .uri(SEND_URL, receiveIdType)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + token)
                    .body(payload)
                    .retrieve()
                    .body(String.class);
            JsonNode root = objectMapper.readTree(body);
            if (root.path("code").asInt(-1) != 0) {
                throw new IllegalStateException("发送飞书消息失败: " + body);
            }
            return root.path("data").path("message_id").asText();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("发送飞书消息异常", e);
        }
    }

    public ObjectNode buildConsultCard(String patientName, long patientId, String diagnosis,
                                       String question, Long alertId) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("config", objectMapper.createObjectNode().put("wide_screen_mode", true));
        ObjectNode header = root.putObject("header");
        header.put("template", "orange");
        ObjectNode titleNode = header.putObject("title");
        titleNode.put("tag", "plain_text");
        titleNode.put("content", "患者咨询待医生回复");

        ArrayNode elements = root.putArray("elements");
        ObjectNode div = elements.addObject();
        div.put("tag", "div");
        ObjectNode text = div.putObject("text");
        text.put("tag", "lark_md");
        text.put("content",
                "**患者：** " + nullToDash(patientName) + "（ID=" + patientId + "）\n"
                        + "**诊断：** " + nullToDash(diagnosis) + "\n"
                        + "**问题：** " + nullToDash(question) + "\n\n"
                        + "此卡片仅对应该患者，请勿与其他患者信息混淆。\n"
                        + "点「我来回复」后，在本群直接输入回复内容即可发给该患者。");

        ObjectNode action = elements.addObject();
        action.put("tag", "action");
        ArrayNode actions = action.putArray("actions");
        actions.add(callbackButton("我来回复", "primary", "REPLY_CONSULT", alertId));
        actions.add(callbackButton("已阅观察", "default", "OBSERVE", alertId));
        actions.add(callbackButton("建议提前复诊", "danger", "EARLY_RECHECK", alertId));
        return root;
    }

    public ObjectNode buildAlertCard(String patientName, String level, String reason, Long alertId) {
        return buildAlertCard(patientName, level, reason, alertId, 0L, "", "", "", reason);
    }

    public ObjectNode buildAlertCard(String patientName, String level, String reason, Long alertId,
                                     long planId, String ruleId, String matchedKeyword,
                                     String suggestedAction, String evidenceSummary) {
        String safeLevel = level == null ? "YELLOW" : level.toUpperCase();
        String title = switch (safeLevel) {
            case "RED" -> "红警 · 请立即处理";
            case "GREEN" -> "绿 · 信息同步";
            case "CONSULT" -> "患者咨询 · 待回复";
            default -> "黄警 · 请尽快查看";
        };
        String template = switch (safeLevel) {
            case "RED" -> "red";
            case "GREEN" -> "green";
            default -> "orange";
        };

        ObjectNode root = objectMapper.createObjectNode();
        root.put("config", objectMapper.createObjectNode().put("wide_screen_mode", true));

        ObjectNode header = root.putObject("header");
        header.put("template", template);
        ObjectNode titleNode = header.putObject("title");
        titleNode.put("tag", "plain_text");
        titleNode.put("content", title + " · " + nullToDash(patientName));

        ArrayNode elements = root.putArray("elements");

        ObjectNode div = elements.addObject();
        div.put("tag", "div");
        ObjectNode text = div.putObject("text");
        text.put("tag", "lark_md");
        StringBuilder body = new StringBuilder();
        body.append("**患者：** ").append(nullToDash(patientName)).append("\n");
        body.append("**级别：** ").append(safeLevel).append("\n");
        body.append("**原因：** ").append(nullToDash(reason)).append("\n");
        body.append("**建议动作：** ").append(nullToDash(suggestedAction)).append("\n");
        body.append("**知识库规则：** ").append(nullToDash(ruleId));
        if (matchedKeyword != null && !matchedKeyword.isBlank()) {
            body.append("（命中：").append(matchedKeyword).append("）");
        }
        body.append("\n");
        if (evidenceSummary != null && !evidenceSummary.isBlank()) {
            body.append("**可信依据：**\n").append(evidenceSummary).append("\n");
        }
        body.append("**告警ID：** ").append(alertId == null ? "-" : alertId);
        text.put("content", body.toString());

        ObjectNode action = elements.addObject();
        action.put("tag", "action");
        ArrayNode actions = action.putArray("actions");
        if ("CONSULT".equals(safeLevel)) {
            actions.add(callbackButton("我来回复", "primary", "REPLY_CONSULT", alertId, planId, null));
        }
        // 飞书工作台闭环按钮
        actions.add(callbackButton("已阅", "primary", "ACK_READ", alertId, planId, null));
        actions.add(callbackButton("电话随访", "default", "CALL_FOLLOWUP", alertId, planId, null));
        actions.add(callbackButton("调整计划", "default", "EDIT_PLAN", alertId, planId, null));
        actions.add(callbackButton("忽略并注明原因", "danger", "IGNORE_NOTE", alertId, planId, null));

        ObjectNode action2 = elements.addObject();
        action2.put("tag", "action");
        ArrayNode actions2 = action2.putArray("actions");
        actions2.add(callbackButton("提前复诊", "danger", "EARLY_RECHECK", alertId, planId, null));
        actions2.add(callbackButton("通知患者", "default", "NOTIFY_PATIENT", alertId, planId, null));

        return root;
    }

    public ObjectNode buildAdaptPlanCard(String patientName, Long planId, long patientId,
                                         String bodyMd, String applyNl) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("config", objectMapper.createObjectNode().put("wide_screen_mode", true));
        ObjectNode header = root.putObject("header");
        header.put("template", "blue");
        ObjectNode titleNode = header.putObject("title");
        titleNode.put("tag", "plain_text");
        titleNode.put("content", "计划自适应建议 · " + nullToDash(patientName));

        ArrayNode elements = root.putArray("elements");
        ObjectNode div = elements.addObject();
        div.put("tag", "div");
        ObjectNode text = div.putObject("text");
        text.put("tag", "lark_md");
        text.put("content", bodyMd == null ? "-" : bodyMd);

        ObjectNode action = elements.addObject();
        action.put("tag", "action");
        ArrayNode actions = action.putArray("actions");
        actions.add(callbackButton("确认采纳", "primary", "CONFIRM_ADAPT", 0L, planId, applyNl));
        actions.add(callbackButton("稍后处理", "default", "PLAN_LATER", 0L, planId, null));
        return root;
    }

    public ObjectNode buildCarePlanCard(String patientName, String diagnosis, Long planId, ObjectNode content) {
        return buildCarePlanCard(patientName, diagnosis, planId, content, null);
    }

    /**
     * 离院表单：科室、患者、病症、病种包、随访天数、采集时间、
     * 统一术后随访表（通用+病种）、日程、医生补充。
     */
    public ObjectNode buildCarePlanCard(String patientName, String diagnosis, Long planId,
                                        ObjectNode content, String editUrl) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("config", objectMapper.createObjectNode().put("wide_screen_mode", true));

        ObjectNode header = root.putObject("header");
        header.put("template", "blue");
        ObjectNode titleNode = header.putObject("title");
        titleNode.put("tag", "plain_text");
        titleNode.put("content", "离院随访表单待确认");

        ArrayNode elements = root.putArray("elements");
        ObjectNode div = elements.addObject();
        div.put("tag", "div");
        ObjectNode text = div.putObject("text");
        text.put("tag", "lark_md");

        String specialty = content != null && content.hasNonNull("specialty")
                ? content.path("specialty").asText() : "骨科";
        String dx = content != null && content.hasNonNull("diagnosis")
                ? content.path("diagnosis").asText() : diagnosis;
        String diseaseTitle = content != null && content.hasNonNull("diseaseTitle")
                ? content.path("diseaseTitle").asText() : "按诊断自动匹配";
        int days = content != null ? content.path("followupDays").asInt(14) : 14;
        boolean collectOn = content == null || !content.has("dailyCollectEnabled")
                || content.path("dailyCollectEnabled").asBoolean(true);
        int hour = content != null ? content.path("dailyCollectHour").asInt(20) : 20;
        int minute = content != null ? content.path("dailyCollectMinute").asInt(0) : 0;
        String timeLabel = String.format("%d:%02d", hour, Math.max(0, Math.min(minute, 59)));

        StringBuilder sb = new StringBuilder();
        sb.append("**【离院随访表单】** 计划#").append(planId == null ? "-" : planId).append("\n\n");
        sb.append("**1. 科室**\n").append(nullToDash(specialty)).append("\n\n");
        sb.append("**2. 患者姓名**\n").append(nullToDash(patientName)).append("\n\n");
        sb.append("**3. 病症**\n").append(nullToDash(dx)).append("\n\n");
        sb.append("**4. 病种随访包**\n").append(nullToDash(diseaseTitle)).append("\n\n");
        sb.append("**5. 随访天数**\n").append(days).append(" 天（前7天每日，之后隔日+关键日；下方可改天数）\n\n");
        sb.append("**6. 随访采集时间**\n");
        sb.append(collectOn ? ("每天至多 1 次 · " + timeLabel + "（下方可点选修改）") : "已关闭").append("\n\n");
        sb.append("**7. 统一术后随访表**（骨科通用 + 本病种专属；疼痛：无/轻/中/剧痛）\n");
        JsonNode form = null;
        if (content != null) {
            if (content.has("followupForm")) {
                form = content.path("followupForm");
            } else if (content.has("dailyCollectForm")) {
                form = content.path("dailyCollectForm");
            }
        }
        sb.append(formatFormBrief(form));
        sb.append("\n**8. 随访日程**\n");
        if (content != null && content.path("nodes").isArray() && !content.path("nodes").isEmpty()) {
            List<String> daysLabel = new ArrayList<>();
            for (var node : content.path("nodes")) {
                String title = node.path("title").asText("");
                daysLabel.add("D" + node.path("day").asInt()
                        + (title.isBlank() ? "" : " " + title));
            }
            sb.append(String.join(" · ", daysLabel)).append("\n");
        } else {
            sb.append("（确认后按日程策略生成）\n");
        }
        sb.append("\n**9. 医生补充**\n");
        boolean hasExtra = false;
        if (content != null && content.path("doctorExtras").isArray()) {
            for (var e : content.path("doctorExtras")) {
                String line = e.isTextual() ? e.asText() : e.path("question").asText(e.asText());
                sb.append("- ").append(line).append("\n");
                hasExtra = true;
            }
        }
        if (content != null && content.hasNonNull("careTips") && !content.path("careTips").asText("").isBlank()) {
            sb.append("- 照护要点：").append(content.path("careTips").asText()).append("\n");
            hasExtra = true;
        }
        if (!hasExtra) {
            sb.append("暂无。可点「在飞书中修改」后发送：加要求：……\n");
        }
        sb.append("\n---\n患者 App 每次只答一张统一随访表。");
        text.put("content", sb.toString());

        ObjectNode selDays = elements.addObject();
        selDays.put("tag", "action");
        ArrayNode dayActions = selDays.putArray("actions");
        dayActions.add(selectStatic("选择随访天数", "SET_FOLLOWUP_DAYS", planId,
                List.of("3", "7", "10", "14", "21", "30"), "天"));

        ObjectNode selTime = elements.addObject();
        selTime.put("tag", "action");
        ArrayNode timeActions = selTime.putArray("actions");
        timeActions.add(selectStatic("选择随访采集时间", "SET_DAILY_TIME", planId,
                List.of("8:00", "9:00", "9:55", "10:00", "12:00", "18:00", "20:00", "21:00"), ""));

        ObjectNode action = elements.addObject();
        action.put("tag", "action");
        ArrayNode actions = action.putArray("actions");
        actions.add(callbackButton("确认出院并生成随访码", "primary", "CONFIRM_PLAN", planId));
        actions.add(callbackButton("在飞书中修改表单", "default", "EDIT_PLAN", planId));
        actions.add(callbackButton("稍后处理", "default", "PLAN_LATER", planId));
        return root;
    }

    private ObjectNode selectStatic(String placeholder, String actionCode, Long planId,
                                    List<String> options, String suffix) {
        ObjectNode sel = objectMapper.createObjectNode();
        sel.put("tag", "select_static");
        ObjectNode ph = sel.putObject("placeholder");
        ph.put("tag", "plain_text");
        ph.put("content", placeholder);
        ObjectNode value = objectMapper.createObjectNode();
        value.put("action", actionCode);
        value.put("planId", planId == null ? 0L : planId);
        value.put("alertId", 0L);
        sel.set("value", value);
        ArrayNode opts = sel.putArray("options");
        for (String o : options) {
            ObjectNode opt = opts.addObject();
            ObjectNode t = opt.putObject("text");
            t.put("tag", "plain_text");
            t.put("content", o + (suffix == null || suffix.isBlank() ? "" : suffix));
            opt.put("value", o);
        }
        return sel;
    }

    public ObjectNode buildBriefingCard(String patientName, String risk, String contentMd, Long briefingId) {
        return buildBriefingCard(patientName, risk, contentMd, briefingId, 0L, 0L);
    }

    public ObjectNode buildBriefingCard(String patientName, String risk, String contentMd, Long briefingId,
                                        long patientId, long planId) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("config", objectMapper.createObjectNode().put("wide_screen_mode", true));

        ObjectNode header = root.putObject("header");
        String template = "RED".equalsIgnoreCase(risk) ? "red" : "YELLOW".equalsIgnoreCase(risk) ? "orange" : "green";
        header.put("template", template);
        ObjectNode titleNode = header.putObject("title");
        titleNode.put("tag", "plain_text");
        titleNode.put("content", "诊前30秒 · " + nullToDash(patientName));

        ArrayNode elements = root.putArray("elements");
        ObjectNode div = elements.addObject();
        div.put("tag", "div");
        ObjectNode text = div.putObject("text");
        text.put("tag", "lark_md");
        String body = contentMd == null ? "-" : contentMd;
        if (body.length() > 1800) {
            body = body.substring(0, 1800) + "\n...(已截断，完整见库)";
        }
        text.put("content", body + "\n\n简报ID：" + (briefingId == null ? "-" : briefingId));

        ObjectNode action = elements.addObject();
        action.put("tag", "action");
        ArrayNode actions = action.putArray("actions");
        long alertBiz = briefingId == null ? 0L : briefingId;
        actions.add(callbackButton("已知晓", "primary", "BRIEF_ACK", alertBiz, planId, null));
        actions.add(callbackButton("需护士今日电话", "default", "BRIEF_CALL", alertBiz, planId, null));
        actions.add(callbackButton("建议提前加号", "default", "BRIEF_EARLY", alertBiz, planId, null));
        actions.add(callbackButton("忽略并注明", "danger", "BRIEF_IGNORE_NOTE", alertBiz, planId, null));
        return root;
    }

    private ObjectNode callbackButton(String label, String type, String actionCode, Long bizId) {
        return callbackButton(label, type, actionCode, bizId, 0L, null);
    }

    private ObjectNode callbackButton(String label, String type, String actionCode,
                                      Long alertOrBizId, long planId, String applyNl) {
        ObjectNode btn = objectMapper.createObjectNode();
        btn.put("tag", "button");
        btn.put("type", type);
        ObjectNode textNode = btn.putObject("text");
        textNode.put("tag", "plain_text");
        textNode.put("content", label);

        ObjectNode value = objectMapper.createObjectNode();
        value.put("action", actionCode);
        boolean planAction = "CONFIRM_PLAN".equals(actionCode) || "PLAN_LATER".equals(actionCode)
                || "EDIT_PLAN".equals(actionCode) || "CONFIRM_ADAPT".equals(actionCode);
        if (planAction) {
            long pid = planId > 0 ? planId : (alertOrBizId == null ? 0L : alertOrBizId);
            value.put("planId", pid);
            value.put("alertId", "EDIT_PLAN".equals(actionCode) && planId <= 0
                    ? (alertOrBizId == null ? 0L : alertOrBizId) : 0L);
        } else {
            value.put("alertId", alertOrBizId == null ? 0L : alertOrBizId);
            value.put("planId", planId);
        }
        if (applyNl != null && !applyNl.isBlank()) {
            // 飞书 value 有长度限制，过长时截断；完整 NL 也可由服务端按 planId 重算
            String nl = applyNl.length() > 200 ? applyNl.substring(0, 200) : applyNl;
            value.put("applyNl", nl);
        }
        btn.set("value", value);
        return btn;
    }

    private String nullToDash(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }

    private String formatFormBrief(JsonNode form) {
        if (form == null || !form.has("items")) {
            return "  （统一术后随访：相对昨天 / 体温 / 疼痛四档 / 伤口 / 用药 / 急症 / 病种专属）\n";
        }
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (JsonNode item : form.path("items")) {
            if ("section".equals(item.path("type").asText())) {
                sb.append("\n  **").append(item.path("label").asText()).append("**\n");
                continue;
            }
            sb.append("  ").append(i++).append(". ").append(item.path("label").asText());
            if (item.path("options").isArray() && !item.path("options").isEmpty()) {
                List<String> opts = new ArrayList<>();
                for (JsonNode o : item.path("options")) {
                    opts.add(o.asText());
                }
                sb.append("\n     可选：").append(String.join(" ｜ ", opts));
                if (item.path("allowCustom").asBoolean(true)) {
                    sb.append("\n     自定义：").append(item.path("customHint").asText("可补充描述"));
                }
            } else {
                sb.append("\n     自定义描述");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String trimFeishu(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
