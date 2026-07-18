package com.careloop.kb;

import com.careloop.ortho.OrthoKnowledgeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从飞书多维表格同步骨科知识库；也可把本地种子灌入多维表格。
 */
@Service
public class OrthoBitableKnowledgeSync {

    private static final Logger log = LoggerFactory.getLogger(OrthoBitableKnowledgeSync.class);

    private final BitableKbProperties properties;
    private final FeishuBitableClient bitableClient;
    private final OrthoKnowledgeService knowledgeService;
    private final ObjectMapper objectMapper;

    private volatile String lastSource = "local-json";
    private volatile String lastSyncAt = "";
    private volatile String lastError = "";

    public OrthoBitableKnowledgeSync(BitableKbProperties properties,
                                     FeishuBitableClient bitableClient,
                                     OrthoKnowledgeService knowledgeService,
                                     ObjectMapper objectMapper) {
        this.properties = properties;
        this.bitableClient = bitableClient;
        this.knowledgeService = knowledgeService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void maybeSyncOnStartup() {
        if (properties.isReady() && properties.isSyncOnStartup()) {
            try {
                syncFromBitable();
            } catch (Exception e) {
                log.warn("启动时同步多维表格知识库失败，继续使用本地 JSON: {}", e.getMessage());
                lastError = e.getMessage();
            }
        }
    }

    @Scheduled(fixedDelayString = "${careloop.kb.bitable.sync-interval-ms:600000}")
    public void scheduledSync() {
        if (!properties.isReady() || properties.getSyncIntervalMs() <= 0) {
            return;
        }
        try {
            syncFromBitable();
        } catch (Exception e) {
            log.warn("定时同步多维表格失败: {}", e.getMessage());
            lastError = e.getMessage();
        }
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", properties.isEnabled());
        m.put("ready", properties.isReady());
        m.put("appTokenConfigured", properties.getAppToken() != null && !properties.getAppToken().isBlank());
        m.put("lastSource", lastSource);
        m.put("lastSyncAt", lastSyncAt);
        m.put("lastError", lastError);
        m.putAll(knowledgeService.summary());
        return m;
    }

    public synchronized Map<String, Object> syncFromBitable() {
        if (!properties.isReady()) {
            throw new IllegalStateException("多维表格知识库未配置（enabled/app-token/table-nodes/table-rules）");
        }
        String app = properties.getAppToken();

        List<JsonNode> nodeRows = bitableClient.listAllRecords(app, properties.getTableNodes());
        List<JsonNode> ruleRows = bitableClient.listAllRecords(app, properties.getTableRules());
        List<JsonNode> domainRows = blank(properties.getTableDomains())
                ? List.of()
                : bitableClient.listAllRecords(app, properties.getTableDomains());
        List<JsonNode> replyRows = blank(properties.getTableReplies())
                ? List.of()
                : bitableClient.listAllRecords(app, properties.getTableReplies());
        List<JsonNode> briefRows = blank(properties.getTableBriefing())
                ? List.of()
                : bitableClient.listAllRecords(app, properties.getTableBriefing());

        ObjectNode root = objectMapper.createObjectNode();
        root.put("id", "ortho-tka-bitable");
        root.put("title", text(findReply(replyRows, "title"), "骨科·膝关节置换术后知识库（多维表格）"));
        root.put("specialty", text(findReply(replyRows, "specialty"), "骨科·关节置换"));
        root.put("version", text(findReply(replyRows, "version"), "bitable"));
        root.put("disclaimer", text(findReply(replyRows, "disclaimer"),
                "本知识库用于院外随访辅助，不替代面诊与急诊判断。"));
        root.put("careTips", text(findReply(replyRows, "careTips"), knowledgeService.careTips()));
        root.put("welcomeExtra", text(findReply(replyRows, "welcomeExtra"),
                knowledgeService.root().path("welcomeExtra").asText("")));

        ArrayNode domains = root.putArray("monitoringDomains");
        for (JsonNode row : domainRows) {
            ObjectNode d = domains.addObject();
            d.put("id", field(row, "域ID", "id"));
            d.put("name", field(row, "名称", "name"));
            d.put("why", field(row, "目的", "why"));
            putSplitArray(d, "greenSignals", field(row, "绿信号", "greenSignals"));
            putSplitArray(d, "yellowSignals", field(row, "黄信号", "yellowSignals"));
            putSplitArray(d, "redSignals", field(row, "红信号", "redSignals"));
        }
        if (domains.isEmpty()) {
            domains.addAll((ArrayNode) knowledgeService.root().path("monitoringDomains"));
        }

        ArrayNode rules = root.putArray("triageRules");
        for (JsonNode row : ruleRows) {
            ObjectNode r = rules.addObject();
            r.put("level", field(row, "级别", "level").toUpperCase());
            r.put("category", field(row, "类别", "category"));
            putSplitArray(r, "keywords", field(row, "关键词", "keywords"));
            r.put("reasonTemplate", field(row, "原因模板", "reasonTemplate"));
        }
        if (rules.isEmpty()) {
            throw new IllegalStateException("分诊规则表为空，请先灌入种子数据");
        }

        List<JsonNode> sortedNodes = new ArrayList<>(nodeRows);
        sortedNodes.sort(Comparator.comparingInt(r -> (int) num(r, "天数", "day")));
        ArrayNode nodes = root.putArray("followupNodes");
        for (JsonNode row : sortedNodes) {
            ObjectNode n = nodes.addObject();
            n.put("day", (int) num(row, "天数", "day"));
            n.put("title", field(row, "标题", "title"));
            n.put("question", field(row, "问题", "question"));
            putSplitArray(n, "focus", field(row, "关注域", "focus"));
            putSplitArray(n, "coachTips", field(row, "教练提示", "coachTips"));
        }
        if (nodes.isEmpty()) {
            throw new IllegalStateException("随访节点表为空，请先灌入种子数据");
        }

        ObjectNode replies = root.putObject("patientReplies");
        replies.put("green", text(findReply(replyRows, "green"), knowledgeService.replyForLevel("GREEN")));
        replies.put("yellow", text(findReply(replyRows, "yellow"), knowledgeService.replyForLevel("YELLOW")));
        replies.put("red", text(findReply(replyRows, "red"), knowledgeService.replyForLevel("RED")));
        replies.put("notifyAfterDoctor", text(findReply(replyRows, "notifyAfterDoctor"), knowledgeService.notifyAfterDoctor()));
        replies.put("earlyRecheck", text(findReply(replyRows, "earlyRecheck"), knowledgeService.earlyRecheckMessage()));

        ArrayNode briefing = root.putArray("briefingAskList");
        briefRows.sort(Comparator.comparingInt(r -> (int) num(r, "序号", "sort")));
        for (JsonNode row : briefRows) {
            String item = field(row, "检查项", "item");
            if (!item.isBlank()) {
                briefing.add(item);
            }
        }
        if (briefing.isEmpty()) {
            for (JsonNode n : knowledgeService.root().path("briefingAskList")) {
                briefing.add(n.asText());
            }
        }

        knowledgeService.replaceRoot(root, "feishu-bitable");
        lastSource = "feishu-bitable";
        lastSyncAt = java.time.LocalDateTime.now().toString();
        lastError = "";

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("synced", true);
        result.put("source", lastSource);
        result.put("syncedAt", lastSyncAt);
        result.put("nodes", nodes.size());
        result.put("rules", rules.size());
        result.put("domains", domains.size());
        result.putAll(knowledgeService.summary());
        return result;
    }

    /** 把当前本地/内存知识库灌入多维表格（用于初始化） */
    public Map<String, Object> seedBitableFromLocal() {
        if (!properties.isReady()) {
            throw new IllegalStateException("多维表格未配置");
        }
        JsonNode src = knowledgeService.root();
        String app = properties.getAppToken();

        List<ObjectNode> nodes = new ArrayList<>();
        for (JsonNode n : src.path("followupNodes")) {
            ObjectNode f = objectMapper.createObjectNode();
            f.put("天数", n.path("day").asInt());
            f.put("标题", n.path("title").asText());
            f.put("问题", n.path("question").asText());
            f.put("关注域", join(n.path("focus")));
            f.put("教练提示", join(n.path("coachTips")));
            nodes.add(f);
        }
        bitableClient.batchCreate(app, properties.getTableNodes(), nodes);

        List<ObjectNode> rules = new ArrayList<>();
        for (JsonNode r : src.path("triageRules")) {
            ObjectNode f = objectMapper.createObjectNode();
            f.put("级别", r.path("level").asText());
            f.put("类别", r.path("category").asText());
            f.put("关键词", join(r.path("keywords")));
            f.put("原因模板", r.path("reasonTemplate").asText());
            rules.add(f);
        }
        bitableClient.batchCreate(app, properties.getTableRules(), rules);

        int domainCount = 0;
        if (!blank(properties.getTableDomains())) {
            List<ObjectNode> domains = new ArrayList<>();
            for (JsonNode d : src.path("monitoringDomains")) {
                ObjectNode f = objectMapper.createObjectNode();
                f.put("域ID", d.path("id").asText());
                f.put("名称", d.path("name").asText());
                f.put("目的", d.path("why").asText());
                f.put("绿信号", join(d.path("greenSignals")));
                f.put("黄信号", join(d.path("yellowSignals")));
                f.put("红信号", join(d.path("redSignals")));
                domains.add(f);
            }
            bitableClient.batchCreate(app, properties.getTableDomains(), domains);
            domainCount = domains.size();
        }

        int replyCount = 0;
        if (!blank(properties.getTableReplies())) {
            List<ObjectNode> replies = new ArrayList<>();
            putKv(replies, "title", src.path("title").asText());
            putKv(replies, "specialty", src.path("specialty").asText());
            putKv(replies, "version", src.path("version").asText() + "-bitable");
            putKv(replies, "disclaimer", src.path("disclaimer").asText());
            putKv(replies, "careTips", src.path("careTips").asText());
            putKv(replies, "welcomeExtra", src.path("welcomeExtra").asText());
            JsonNode pr = src.path("patientReplies");
            putKv(replies, "green", pr.path("green").asText());
            putKv(replies, "yellow", pr.path("yellow").asText());
            putKv(replies, "red", pr.path("red").asText());
            putKv(replies, "notifyAfterDoctor", pr.path("notifyAfterDoctor").asText());
            putKv(replies, "earlyRecheck", pr.path("earlyRecheck").asText());
            bitableClient.batchCreate(app, properties.getTableReplies(), replies);
            replyCount = replies.size();
        }

        int briefCount = 0;
        if (!blank(properties.getTableBriefing())) {
            List<ObjectNode> briefing = new ArrayList<>();
            int i = 1;
            for (JsonNode b : src.path("briefingAskList")) {
                ObjectNode f = objectMapper.createObjectNode();
                f.put("序号", i++);
                f.put("检查项", b.asText());
                briefing.add(f);
            }
            bitableClient.batchCreate(app, properties.getTableBriefing(), briefing);
            briefCount = briefing.size();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("seeded", true);
        result.put("nodes", nodes.size());
        result.put("rules", rules.size());
        result.put("domains", domainCount);
        result.put("replies", replyCount);
        result.put("briefing", briefCount);
        result.put("tip", "种子已写入多维表格，请再调用 /api/kb/ortho/sync-bitable 让助手加载表格内容");
        return result;
    }

    private void putKv(List<ObjectNode> list, String key, String content) {
        ObjectNode f = objectMapper.createObjectNode();
        f.put("键", key);
        f.put("内容", content == null ? "" : content);
        list.add(f);
    }

    private String findReply(List<JsonNode> rows, String key) {
        for (JsonNode row : rows) {
            if (key.equalsIgnoreCase(field(row, "键", "key"))) {
                return field(row, "内容", "content");
            }
        }
        return "";
    }

    private void putSplitArray(ObjectNode parent, String fieldName, String csv) {
        ArrayNode arr = parent.putArray(fieldName);
        if (csv == null || csv.isBlank()) {
            return;
        }
        for (String p : csv.split("[,，、;；]")) {
            String t = p.trim();
            if (!t.isEmpty()) {
                arr.add(t);
            }
        }
    }

    private String field(JsonNode row, String cn, String en) {
        if (row == null) {
            return "";
        }
        if (row.has(cn)) {
            return asPlain(row.get(cn));
        }
        if (row.has(en)) {
            return asPlain(row.get(en));
        }
        return "";
    }

    private double num(JsonNode row, String cn, String en) {
        String s = field(row, cn, en);
        if (s.isBlank()) {
            return 0;
        }
        try {
            return Double.parseDouble(s);
        } catch (Exception e) {
            return 0;
        }
    }

    private String asPlain(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        if (node.isNumber()) {
            // 避免 1.0
            double d = node.asDouble();
            if (Math.abs(d - Math.rint(d)) < 1e-6) {
                return String.valueOf((long) Math.rint(d));
            }
            return String.valueOf(d);
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode n : node) {
                if (n.isTextual()) {
                    parts.add(n.asText());
                } else if (n.has("text")) {
                    parts.add(n.path("text").asText());
                } else if (n.has("name")) {
                    parts.add(n.path("name").asText());
                } else {
                    parts.add(n.asText());
                }
            }
            return String.join(",", parts);
        }
        if (node.isObject() && node.has("text")) {
            return node.path("text").asText();
        }
        return node.asText();
    }

    private String join(JsonNode arr) {
        List<String> parts = new ArrayList<>();
        for (JsonNode n : arr) {
            parts.add(n.asText());
        }
        return String.join(",", parts);
    }

    private String text(String v, String def) {
        return v == null || v.isBlank() ? def : v;
    }

    private boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
