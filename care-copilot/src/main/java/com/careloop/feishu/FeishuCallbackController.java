package com.careloop.feishu;

import com.careloop.alert.AlertActionService;
import com.careloop.config.FeishuProperties;
import com.careloop.feishu.FeishuDoctorMessageRouter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/feishu")
public class FeishuCallbackController {

    private static final Logger log = LoggerFactory.getLogger(FeishuCallbackController.class);

    private final FeishuProperties properties;
    private final AlertActionService alertActionService;
    private final FeishuDoctorMessageRouter doctorMessageRouter;
    private final ObjectMapper objectMapper;

    public FeishuCallbackController(FeishuProperties properties,
                                    AlertActionService alertActionService,
                                    FeishuDoctorMessageRouter doctorMessageRouter,
                                    ObjectMapper objectMapper) {
        this.properties = properties;
        this.alertActionService = alertActionService;
        this.doctorMessageRouter = doctorMessageRouter;
        this.objectMapper = objectMapper;
    }

    @PostMapping(value = "/callback", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Object callback(@RequestBody JsonNode body) {
        log.info("收到飞书回调: {}", body);

        if (body.hasNonNull("challenge") && "url_verification".equals(body.path("type").asText(null))) {
            return Map.of("challenge", body.path("challenge").asText());
        }
        if (body.hasNonNull("challenge") && !body.has("header")) {
            return Map.of("challenge", body.path("challenge").asText());
        }

        String eventType = body.path("header").path("event_type").asText("");
        if ("card.action.trigger".equals(eventType)) {
            return handleCardAction(body.path("event"));
        }
        if ("im.message.receive_v1".equals(eventType)) {
            doctorMessageRouter.route(body.path("event"));
            return Map.of("code", 0);
        }

        if (body.has("action")) {
            return handleLegacyCardAction(body);
        }

        return Map.of("code", 0);
    }

    private Object handleCardAction(JsonNode event) {
        JsonNode action = event.path("action");
        JsonNode value = action.path("value");
        if (value.isMissingNode() || value.isNull()) {
            value = action.path("option");
        }

        String actionCode = value.path("action").asText("");
        long alertId = value.path("alertId").asLong(0L);
        long planId = value.path("planId").asLong(0L);
        String draftKey = value.path("draftKey").asText("");
        String applyNl = value.path("applyNl").asText("");
        // 下拉选项：飞书把选中值放在 action.option
        String option = action.path("option").asText("");
        if (option.isBlank()) {
            option = value.path("option").asText("");
        }
        if (!option.isBlank() && applyNl.isBlank()) {
            applyNl = option;
        }
        String operator = event.path("operator").path("open_id").asText("unknown");
        String openChatId = event.path("context").path("open_chat_id").asText("");
        if (openChatId.isBlank()) {
            openChatId = event.path("open_chat_id").asText("");
        }

        String toast = alertActionService.handleDoctorAction(
                alertId, planId, actionCode, operator, openChatId, draftKey, applyNl
        );

        ObjectNode resp = objectMapper.createObjectNode();
        ObjectNode toastNode = resp.putObject("toast");
        toastNode.put("type", "success");
        toastNode.put("content", toast);
        return resp;
    }

    private Object handleLegacyCardAction(JsonNode body) {
        JsonNode value = body.path("action").path("value");
        String actionCode = value.path("action").asText("");
        long alertId = value.path("alertId").asLong(0L);
        long planId = value.path("planId").asLong(0L);
        String draftKey = value.path("draftKey").asText("");
        String applyNl = value.path("applyNl").asText("");
        String operator = body.path("open_id").asText("unknown");
        String toast = alertActionService.handleDoctorAction(
                alertId, planId, actionCode, operator, null, draftKey, applyNl
        );
        return Map.of(
                "toast", Map.of(
                        "type", "info",
                        "content", toast
                )
        );
    }
}
