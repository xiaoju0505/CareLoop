package com.careloop.caseintake;

import com.careloop.care.CarePlanService;
import com.careloop.feishu.FeishuMessageResourceService;
import com.careloop.feishu.FeishuMessageService;
import com.careloop.patient.PatientService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 飞书内上传/粘贴电子病历 → 自动识别 → 确认建档（可顺带生成随访计划）。
 */
@Service
public class FeishuCaseIntakeService {

    private static final Logger log = LoggerFactory.getLogger(FeishuCaseIntakeService.class);

    private final CaseParseService parseService;
    private final CaseDraftStore draftStore;
    private final PatientService patientService;
    private final CarePlanService carePlanService;
    private final FeishuMessageService feishuMessageService;
    private final FeishuMessageResourceService resourceService;
    private final DocxTextExtractor docxTextExtractor;
    private final ObjectMapper objectMapper;

    @Value("${careloop.demo.feishu-receive-id-type:chat_id}")
    private String defaultReceiveIdType;

    @Value("${careloop.demo.feishu-receive-id:}")
    private String defaultReceiveId;

    public FeishuCaseIntakeService(CaseParseService parseService,
                                   CaseDraftStore draftStore,
                                   PatientService patientService,
                                   CarePlanService carePlanService,
                                   FeishuMessageService feishuMessageService,
                                   FeishuMessageResourceService resourceService,
                                   DocxTextExtractor docxTextExtractor,
                                   ObjectMapper objectMapper) {
        this.parseService = parseService;
        this.draftStore = draftStore;
        this.patientService = patientService;
        this.carePlanService = carePlanService;
        this.feishuMessageService = feishuMessageService;
        this.resourceService = resourceService;
        this.docxTextExtractor = docxTextExtractor;
        this.objectMapper = objectMapper;
    }

    public boolean looksLikeCaseMessage(String text, String messageType) {
        if (messageType != null) {
            String t = messageType.toLowerCase(Locale.ROOT);
            if (t.contains("file") || t.contains("image") || t.contains("media")) {
                return true;
            }
        }
        if (text == null || text.isBlank()) {
            return false;
        }
        String s = text.replace(" ", "");
        return s.contains("建档") || s.contains("病例") || s.contains("病历") || s.contains("出院小结")
                || s.contains("出院记录") || s.contains("电子病历") || s.contains("诊断")
                || s.contains("膝关节") || s.contains("|") || s.contains("｜");
    }

    public Map<String, Object> intakeFromText(String text, String receiveIdType, String receiveId) {
        Map<String, String> fields = parseService.parse(text);
        String type = blank(receiveIdType) ? defaultReceiveIdType : receiveIdType;
        String chat = blank(receiveId) ? defaultReceiveId : receiveId;
        CaseDraftStore.Draft draft = draftStore.save(fields, chat, null, "text");
        String msgId = feishuMessageService.sendInteractive(type, chat, buildConfirmCard(draft));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("draftKey", draft.key());
        result.put("fields", fields);
        result.put("messageId", msgId);
        result.put("tip", "已推送飞书识别确认卡片，医生点确认即可建档");
        return result;
    }

    public void handleImMessageEvent(JsonNode event) {
        try {
            JsonNode message = event.path("message");
            String messageId = message.path("message_id").asText("");
            String chatId = message.path("chat_id").asText(defaultReceiveId);
            String messageType = message.path("message_type").asText("text");
            String contentJson = message.path("content").asText("{}");

            JsonNode contentNode = readContent(contentJson);
            String text = extractText(messageType, contentNode);

            if (!looksLikeCaseMessage(text, messageType)) {
                return;
            }

            String parseSource = text;
            String sourceLabel = messageType;

            if ("file".equals(messageType)) {
                String fileName = contentNode.path("file_name").asText("未命名");
                String fileKey = contentNode.path("file_key").asText("");
                String extracted = downloadAndExtract(messageId, fileKey, fileName);
                draftStore.putPendingFile(new CaseDraftStore.PendingFile(
                        chatId, messageId, fileKey, fileName, extracted, Instant.now()));
                if (!blank(extracted)) {
                    parseSource = extracted;
                    sourceLabel = "docx";
                } else {
                    parseSource = "【病例附件】" + fileName
                            + "\n未能自动读取正文（请确认已开通 im:resource，或粘贴出院小结文字）。"
                            + "\n建档|姓名|女|诊断|医生|医嘱";
                    sourceLabel = "file";
                }
            } else if (isThinCaseHint(text)) {
                CaseDraftStore.PendingFile pending = draftStore.getPendingFile(chatId);
                if (pending != null && !blank(pending.extractedText())) {
                    parseSource = pending.extractedText();
                    sourceLabel = "pending-file";
                    draftStore.clearPendingFile(chatId);
                } else if (pending != null) {
                    // 有附件但没抽出正文：再试一次下载
                    String again = downloadAndExtract(pending.messageId(), pending.fileKey(), pending.fileName());
                    if (!blank(again)) {
                        parseSource = again;
                        sourceLabel = "pending-file-retry";
                        draftStore.clearPendingFile(chatId);
                    } else {
                        feishuMessageService.sendText("chat_id", chatId,
                                "已收到附件「" + pending.fileName() + "」，但未能读取正文。\n"
                                        + "请粘贴出院小结文字，或用快捷格式：\n"
                                        + "建档|张女士|女|膝关节置换术后|李医生|抬高患肢，按时抗凝");
                        return;
                    }
                } else {
                    feishuMessageService.sendText("chat_id", chatId,
                            "请先发送病例 Word/文字，或直接粘贴出院小结。\n"
                                    + "快捷格式：建档|姓名|女|诊断|医生|医嘱");
                    return;
                }
            }

            Map<String, String> fields = parseService.parse(parseSource);
            if ("file".equals(messageType) || "docx".equals(sourceLabel) || sourceLabel.startsWith("pending")) {
                fields.put("caseNotes", mergeNotes(fields.get("caseNotes"),
                        "[来源附件] " + (contentNode.path("file_name").asText("病例文件"))));
            }

            CaseDraftStore.Draft draft = draftStore.save(fields, chatId, messageId, sourceLabel);
            feishuMessageService.sendInteractive("chat_id", chatId, buildConfirmCard(draft));
            log.info("病例识别草稿已推送 draftKey={} name={} source={}", draft.key(), fields.get("name"), sourceLabel);
        } catch (Exception e) {
            log.warn("处理飞书病例消息失败: {}", e.getMessage());
        }
    }

    public String confirmDraft(String draftKey, boolean createPlan, String openChatId) {
        CaseDraftStore.Draft draft = draftStore.get(draftKey);
        if (draft == null) {
            return "识别草稿不存在或已确认，请重新发送病例";
        }
        Map<String, Object> patient = patientService.create(draft.fields());
        long patientId = ((Number) patient.get("id")).longValue();
        draftStore.remove(draftKey);

        String chat = !blank(openChatId) ? openChatId : draft.chatId();
        if (blank(chat)) {
            chat = defaultReceiveId;
        }

        if (createPlan) {
            Map<String, Object> plan = carePlanService.createDraftAndNotifyDoctor(patientId, "chat_id", chat);
            return "已建档 patientId=" + patientId + "，并发送离院计划 planId=" + plan.get("planId");
        }
        feishuMessageService.sendText("chat_id", chat,
                "【建档成功】" + patient.get("name") + "（ID=" + patientId + "）\n诊断：" + patient.get("diagnosis"));
        return "已建档 patientId=" + patientId + "（" + patient.get("name") + "）";
    }

    public String rejectDraft(String draftKey) {
        draftStore.remove(draftKey);
        return "已忽略本次识别结果";
    }

    public ObjectNode buildConfirmCard(CaseDraftStore.Draft draft) {
        Map<String, String> f = draft.fields();
        ObjectNode root = objectMapper.createObjectNode();
        root.put("config", objectMapper.createObjectNode().put("wide_screen_mode", true));
        ObjectNode header = root.putObject("header");
        header.put("template", "blue");
        ObjectNode title = header.putObject("title");
        title.put("tag", "plain_text");
        title.put("content", "电子病历识别结果 · 请确认建档");

        ArrayNode elements = root.putArray("elements");
        ObjectNode div = elements.addObject();
        div.put("tag", "div");
        ObjectNode text = div.putObject("text");
        text.put("tag", "lark_md");
        text.put("content",
                "**置信度：** " + f.getOrDefault("confidence", "-") + "\n"
                        + "**姓名：** " + f.getOrDefault("name", "-") + "\n"
                        + "**性别：** " + f.getOrDefault("gender", "-") + "\n"
                        + "**诊断：** " + f.getOrDefault("diagnosis", "-") + "\n"
                        + "**医生：** " + f.getOrDefault("doctorName", "-") + "\n"
                        + "**出院日：** " + f.getOrDefault("dischargeAt", "-") + "\n"
                        + "**病历摘要：** " + trim(f.getOrDefault("caseNotes", ""), 300) + "\n\n"
                        + "确认后写入患者库，并按本病历生成个性化骨科随访计划。");

        ObjectNode action = elements.addObject();
        action.put("tag", "action");
        ArrayNode actions = action.putArray("actions");
        actions.add(btn("确认建档并生成随访计划", "primary", "CONFIRM_CASE_AND_PLAN", draft.key()));
        actions.add(btn("仅确认建档", "default", "CONFIRM_CASE", draft.key()));
        actions.add(btn("识别有误，忽略", "danger", "REJECT_CASE", draft.key()));
        return root;
    }

    private String downloadAndExtract(String messageId, String fileKey, String fileName) {
        if (blank(fileKey)) {
            return "";
        }
        byte[] bytes = resourceService.download(messageId, fileKey, "file");
        if (bytes.length == 0) {
            return "";
        }
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".docx") || looksLikeZip(bytes)) {
            String text = docxTextExtractor.extract(bytes);
            if (!blank(text)) {
                log.info("已从 Word 抽取病例正文 {} 字 file={}", text.length(), fileName);
                return text;
            }
        }
        // 纯文本/未知：尝试 UTF-8
        if (lower.endsWith(".txt") || lower.endsWith(".md")) {
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8).trim();
        }
        return "";
    }

    private boolean looksLikeZip(byte[] bytes) {
        return bytes.length >= 2 && bytes[0] == 'P' && bytes[1] == 'K';
    }

    /** 「这是病例」「上面那个文件是病例」等短提示，本身不含病历正文。 */
    private boolean isThinCaseHint(String text) {
        if (text == null) {
            return true;
        }
        String s = text.replaceAll("\\s+", "");
        if (s.length() <= 40 && (s.contains("病例") || s.contains("病历") || s.contains("附件") || s.contains("文件"))) {
            boolean hasStructure = s.contains("姓名") || s.contains("诊断") || s.contains("|") || s.contains("｜")
                    || s.contains("出院") || s.contains("医嘱") || s.contains("膝关节");
            return !hasStructure;
        }
        return false;
    }

    private ObjectNode btn(String label, String type, String action, String draftKey) {
        ObjectNode btn = objectMapper.createObjectNode();
        btn.put("tag", "button");
        btn.put("type", type);
        ObjectNode t = btn.putObject("text");
        t.put("tag", "plain_text");
        t.put("content", label);
        ObjectNode value = objectMapper.createObjectNode();
        value.put("action", action);
        value.put("draftKey", draftKey);
        value.put("alertId", 0);
        value.put("planId", 0);
        btn.set("value", value);
        return btn;
    }

    private JsonNode readContent(String contentJson) {
        try {
            return objectMapper.readTree(contentJson == null ? "{}" : contentJson);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private String extractText(String messageType, JsonNode c) {
        if ("text".equals(messageType)) {
            return c.path("text").asText("");
        }
        if ("file".equals(messageType)) {
            return "文件：" + c.path("file_name").asText("未命名");
        }
        if ("image".equals(messageType)) {
            return "图片病例附件";
        }
        return c.path("text").asText("");
    }

    private String mergeNotes(String notes, String extra) {
        if (blank(notes)) {
            return extra;
        }
        if (notes.contains(extra)) {
            return notes;
        }
        return notes + "\n" + extra;
    }

    private String trim(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
