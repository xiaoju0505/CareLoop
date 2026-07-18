package com.careloop.feishu;

import com.careloop.ortho.OrthoKnowledgeService;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将骨科知识库发布为飞书云文档，并推送卡片到医生群。
 */
@Service
public class FeishuKnowledgePublisher {

    private static final Logger log = LoggerFactory.getLogger(FeishuKnowledgePublisher.class);
    private static final String CREATE_DOC = "https://open.feishu.cn/open-apis/docx/v1/documents";
    private static final String ADD_BLOCKS =
            "https://open.feishu.cn/open-apis/docx/v1/documents/{documentId}/blocks/{blockId}/children";

    private final FeishuTokenService tokenService;
    private final FeishuMessageService messageService;
    private final OrthoKnowledgeService knowledgeService;
    private final ObjectMapper objectMapper;
    private final RestClient restClient = RestClient.create();

    public FeishuKnowledgePublisher(FeishuTokenService tokenService,
                                    FeishuMessageService messageService,
                                    OrthoKnowledgeService knowledgeService,
                                    ObjectMapper objectMapper) {
        this.tokenService = tokenService;
        this.messageService = messageService;
        this.knowledgeService = knowledgeService;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> publishToFeishu(String receiveIdType, String receiveId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("knowledge", knowledgeService.summary());

        String documentId = null;
        String docUrl = null;
        String createError = null;
        try {
            documentId = createEmptyDoc(knowledgeService.title());
            docUrl = "https://feishu.cn/docx/" + documentId;
            fillDocument(documentId);
            result.put("documentId", documentId);
            result.put("docUrl", docUrl);
            result.put("docCreated", true);
        } catch (Exception e) {
            createError = e.getMessage();
            log.warn("创建飞书知识库文档失败，将改为群内推送全文摘要: {}", createError);
            result.put("docCreated", false);
            result.put("docError", createError);
        }

        // 推送概览卡片
        ObjectNode card = buildKbCard(docUrl, createError);
        String cardMsgId = messageService.sendInteractive(receiveIdType, receiveId, card);
        result.put("cardMessageId", cardMsgId);

        // 若文档失败，分段推送 markdown（保证飞书侧可见知识库）
        if (documentId == null) {
            List<String> chunks = chunk(knowledgeService.toFeishuMarkdown(), 1800);
            List<String> textIds = new ArrayList<>();
            for (String chunk : chunks) {
                textIds.add(messageService.sendText(receiveIdType, receiveId, chunk));
            }
            result.put("fallbackTextMessageIds", textIds);
            result.put("tip", "云文档权限可能未开通，已将知识库全文发到群聊；助手端仍使用内置知识库引擎");
        } else {
            result.put("tip", "骨科知识库云文档已创建，随访助手已内置同一套规则引擎");
            // 再发一条权限提示
            messageService.sendText(receiveIdType, receiveId,
                    "【知识库已发布】\n"
                            + knowledgeService.title() + "\n"
                            + "文档：" + docUrl + "\n"
                            + "若打不开，请在飞书开放平台为应用开通「云文档」相关权限，并把文档分享给测试群成员。\n"
                            + "CareLoop 随访助手已加载该知识库：随访节点 / 红黄绿分诊 / 简报清单均据此生成。");
        }
        return result;
    }

    private String createEmptyDoc(String title) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("title", title);
        String resp = restClient.post()
                .uri(CREATE_DOC)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + tokenService.getTenantAccessToken())
                .body(body)
                .retrieve()
                .body(String.class);
        try {
            JsonNode root = objectMapper.readTree(resp);
            if (root.path("code").asInt(-1) != 0) {
                throw new IllegalStateException("创建文档失败: " + resp);
            }
            return root.path("data").path("document").path("document_id").asText();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("解析创建文档响应失败", e);
        }
    }

    private void fillDocument(String documentId) {
        // 文档根 block_id 通常等于 document_id
        List<ObjectNode> blocks = new ArrayList<>();
        blocks.add(heading1("骨科膝关节置换术后连续照护知识库"));
        blocks.add(textBlock("版本 " + knowledgeService.root().path("version").asText()
                + " · " + knowledgeService.root().path("disclaimer").asText()));
        blocks.add(heading2("照护要点"));
        blocks.add(textBlock(knowledgeService.careTips()));

        blocks.add(heading2("五大监测域"));
        for (JsonNode d : knowledgeService.root().path("monitoringDomains")) {
            blocks.add(heading3(d.path("name").asText()));
            blocks.add(textBlock("目的：" + d.path("why").asText()
                    + "\n绿：" + join(d.path("greenSignals"))
                    + "\n黄：" + join(d.path("yellowSignals"))
                    + "\n红：" + join(d.path("redSignals"))));
        }

        blocks.add(heading2("红黄绿规则"));
        for (JsonNode r : knowledgeService.root().path("triageRules")) {
            blocks.add(textBlock(r.path("level").asText() + " / " + r.path("category").asText()
                    + "：" + join(r.path("keywords"))));
        }

        blocks.add(heading2("随访节点"));
        for (JsonNode n : knowledgeService.root().path("followupNodes")) {
            blocks.add(heading3("D" + n.path("day").asInt() + " " + n.path("title").asText()));
            blocks.add(textBlock(n.path("question").asText()
                    + "\n教练提示：" + join(n.path("coachTips"))));
        }

        blocks.add(heading2("诊前简报检查清单"));
        blocks.add(textBlock(knowledgeService.briefingAskListMarkdown()));

        blocks.add(heading2("助手如何使用"));
        blocks.add(textBlock("CareLoop 随访助手启动时加载本知识库 JSON 引擎：生成随访节点、实时分诊、患者话术、诊前汇总结构均以此为准。飞书文档供医生审阅与答辩展示。"));

        // 分批写入，避免单次过大
        int batch = 20;
        for (int i = 0; i < blocks.size(); i += batch) {
            List<ObjectNode> slice = blocks.subList(i, Math.min(i + batch, blocks.size()));
            appendBlocks(documentId, documentId, slice);
        }
    }

    private void appendBlocks(String documentId, String parentBlockId, List<ObjectNode> children) {
        ObjectNode body = objectMapper.createObjectNode();
        ArrayNode arr = body.putArray("children");
        for (ObjectNode c : children) {
            arr.add(c);
        }
        body.put("index", -1);
        String resp = restClient.post()
                .uri(ADD_BLOCKS, documentId, parentBlockId)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + tokenService.getTenantAccessToken())
                .body(body)
                .retrieve()
                .body(String.class);
        try {
            JsonNode root = objectMapper.readTree(resp);
            if (root.path("code").asInt(-1) != 0) {
                throw new IllegalStateException("写入文档块失败: " + resp);
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("解析写入文档响应失败", e);
        }
    }

    private ObjectNode buildKbCard(String docUrl, String error) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("config", objectMapper.createObjectNode().put("wide_screen_mode", true));
        ObjectNode header = root.putObject("header");
        header.put("template", "indigo");
        ObjectNode title = header.putObject("title");
        title.put("tag", "plain_text");
        title.put("content", "骨科知识库已加载到随访助手");

        ArrayNode elements = root.putArray("elements");
        ObjectNode div = elements.addObject();
        div.put("tag", "div");
        ObjectNode text = div.putObject("text");
        text.put("tag", "lark_md");
        Map<String, Object> s = knowledgeService.summary();
        StringBuilder sb = new StringBuilder();
        sb.append("**").append(s.get("title")).append("**\n");
        sb.append("版本：").append(s.get("version")).append("\n");
        sb.append("监测域：").append(s.get("domainCount"))
                .append(" · 随访节点：").append(s.get("nodeCount"))
                .append(" · 分诊规则：").append(s.get("ruleCount")).append("\n\n");
        sb.append("已用于：随访问题生成、实时红黄绿监控、诊前汇总清单。\n");
        if (docUrl != null) {
            sb.append("\n飞书文档：").append(docUrl);
        } else {
            sb.append("\n云文档创建失败（").append(error == null ? "未知" : error)
                    .append("），知识库全文已发到本群；请给应用开通 docx 权限后重试发布。");
        }
        text.put("content", sb.toString());

        if (docUrl != null) {
            ObjectNode action = elements.addObject();
            action.put("tag", "action");
            ArrayNode actions = action.putArray("actions");
            ObjectNode btn = actions.addObject();
            btn.put("tag", "button");
            btn.put("type", "primary");
            btn.put("url", docUrl);
            ObjectNode bt = btn.putObject("text");
            bt.put("tag", "plain_text");
            bt.put("content", "打开知识库文档");
        }
        return root;
    }

    private ObjectNode heading1(String content) {
        return heading(3, content);
    }

    private ObjectNode heading2(String content) {
        return heading(4, content);
    }

    private ObjectNode heading3(String content) {
        return heading(5, content);
    }

    private ObjectNode heading(int blockType, String content) {
        ObjectNode block = objectMapper.createObjectNode();
        block.put("block_type", blockType);
        String key = switch (blockType) {
            case 3 -> "heading1";
            case 4 -> "heading2";
            default -> "heading3";
        };
        ObjectNode h = block.putObject(key);
        ArrayNode elements = h.putArray("elements");
        ObjectNode el = elements.addObject();
        ObjectNode run = el.putObject("text_run");
        run.put("content", content);
        run.putObject("text_element_style");
        h.putObject("style");
        return block;
    }

    private ObjectNode textBlock(String content) {
        ObjectNode block = objectMapper.createObjectNode();
        block.put("block_type", 2);
        ObjectNode text = block.putObject("text");
        ArrayNode elements = text.putArray("elements");
        // 飞书单块内容不宜过长
        String safe = content == null ? "" : content;
        if (safe.length() > 1500) {
            safe = safe.substring(0, 1500) + "…";
        }
        ObjectNode el = elements.addObject();
        ObjectNode run = el.putObject("text_run");
        run.put("content", safe);
        run.putObject("text_element_style");
        text.putObject("style");
        return block;
    }

    private String join(JsonNode arr) {
        List<String> parts = new ArrayList<>();
        for (JsonNode n : arr) {
            parts.add(n.asText());
        }
        return String.join("、", parts);
    }

    private List<String> chunk(String text, int size) {
        List<String> list = new ArrayList<>();
        if (text == null) {
            return list;
        }
        for (int i = 0; i < text.length(); i += size) {
            list.add(text.substring(i, Math.min(i + size, text.length())));
        }
        return list;
    }
}
