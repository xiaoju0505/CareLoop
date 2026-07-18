package com.careloop.bind;

import com.careloop.feishu.FeishuMessageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 确认出院后发放 8 位专属病患码（飞书展示，患者网页输入登录）。
 */
@Service
public class DischargeQrService {

    private final BindTokenService bindTokenService;
    private final FeishuMessageService feishuMessageService;
    private final ObjectMapper objectMapper;

    @Value("${careloop.demo.feishu-receive-id-type:chat_id}")
    private String defaultReceiveIdType;

    @Value("${careloop.demo.feishu-receive-id:}")
    private String defaultReceiveId;

    public DischargeQrService(BindTokenService bindTokenService,
                              FeishuMessageService feishuMessageService,
                              ObjectMapper objectMapper) {
        this.bindTokenService = bindTokenService;
        this.feishuMessageService = feishuMessageService;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> issueAndPushToFeishu(long patientId, String receiveIdType, String receiveId) {
        Map<String, Object> issued = bindTokenService.issueToken(patientId);
        String code = String.valueOf(issued.get("code"));
        String portalUrl = String.valueOf(issued.get("portalUrl"));
        String patientName = String.valueOf(issued.get("patientName"));
        String diagnosis = String.valueOf(issued.get("diagnosis"));

        String type = blank(receiveIdType) ? defaultReceiveIdType : receiveIdType;
        String id = blank(receiveId) ? defaultReceiveId : receiveId;
        if (blank(id)) {
            throw new IllegalStateException("未指定飞书接收方，无法推送病患码");
        }

        String tip = "【骨科出院 · 专属病患码】\n"
                + "患者：" + patientName + "\n"
                + "诊断：" + diagnosis + "\n"
                + "病患码（8位）：" + code + "\n"
                + "登录页：" + portalUrl + "\n"
                + "请告知患者打开网页，输入上述数字码即可绑定档案并使用「随访助手」。";

        Map<String, Object> result = new LinkedHashMap<>(issued);
        result.put("channel", "WEB_CODE");
        result.put("pushedTo", id);
        result.put("imageOk", false);
        result.put("textMessageId", feishuMessageService.sendText(type, id, tip));
        result.put("cardMessageId", feishuMessageService.sendInteractive(
                type, id, buildCodeCard(patientName, diagnosis, code, portalUrl)));
        return result;
    }

    private ObjectNode buildCodeCard(String patientName, String diagnosis, String code, String portalUrl) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("config", objectMapper.createObjectNode().put("wide_screen_mode", true));
        ObjectNode header = root.putObject("header");
        header.put("template", "turquoise");
        ObjectNode title = header.putObject("title");
        title.put("tag", "plain_text");
        title.put("content", "患者专属病患码");

        ArrayNode elements = root.putArray("elements");
        ObjectNode div = elements.addObject();
        div.put("tag", "div");
        ObjectNode text = div.putObject("text");
        text.put("tag", "lark_md");
        text.put("content",
                "**患者：** " + patientName + "\n"
                        + "**诊断：** " + diagnosis + "\n"
                        + "**病患码：** `" + code + "`\n"
                        + "请患者打开 **" + portalUrl + "** ，输入 8 位码登录。\n"
                        + "登录后可使用：随访助手（计划/日常采集）· 外接设备（模拟手环）。");

        ObjectNode action = elements.addObject();
        action.put("tag", "action");
        ArrayNode actions = action.putArray("actions");
        ObjectNode btn1 = actions.addObject();
        btn1.put("tag", "button");
        btn1.put("type", "primary");
        btn1.put("url", portalUrl);
        ObjectNode t1 = btn1.putObject("text");
        t1.put("tag", "plain_text");
        t1.put("content", "打开登录页");
        return root;
    }

    private boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
