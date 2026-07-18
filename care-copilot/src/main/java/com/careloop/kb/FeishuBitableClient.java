package com.careloop.kb;

import com.careloop.feishu.FeishuTokenService;
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

@Service
public class FeishuBitableClient {

    private static final Logger log = LoggerFactory.getLogger(FeishuBitableClient.class);
    private static final String LIST_URL =
            "https://open.feishu.cn/open-apis/bitable/v1/apps/{appToken}/tables/{tableId}/records";
    private static final String BATCH_CREATE_URL =
            "https://open.feishu.cn/open-apis/bitable/v1/apps/{appToken}/tables/{tableId}/records/batch_create";

    private final FeishuTokenService tokenService;
    private final ObjectMapper objectMapper;
    private final RestClient restClient = RestClient.create();

    public FeishuBitableClient(FeishuTokenService tokenService, ObjectMapper objectMapper) {
        this.tokenService = tokenService;
        this.objectMapper = objectMapper;
    }

    public List<JsonNode> listAllRecords(String appToken, String tableId) {
        List<JsonNode> all = new ArrayList<>();
        String pageToken = null;
        do {
            StringBuilder uri = new StringBuilder(LIST_URL.replace("{appToken}", appToken).replace("{tableId}", tableId));
            uri.append("?page_size=100");
            if (pageToken != null) {
                uri.append("&page_token=").append(pageToken);
            }
            String resp = restClient.get()
                    .uri(uri.toString())
                    .header("Authorization", "Bearer " + tokenService.getTenantAccessToken())
                    .retrieve()
                    .body(String.class);
            try {
                JsonNode root = objectMapper.readTree(resp);
                if (root.path("code").asInt(-1) != 0) {
                    throw new IllegalStateException("列出多维表格记录失败: " + resp);
                }
                JsonNode data = root.path("data");
                for (JsonNode item : data.path("items")) {
                    all.add(item.path("fields"));
                }
                boolean hasMore = data.path("has_more").asBoolean(false);
                pageToken = hasMore ? data.path("page_token").asText(null) : null;
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("解析多维表格记录失败", e);
            }
        } while (pageToken != null && !pageToken.isBlank());
        log.info("多维表格 {} 拉取 {} 条", tableId, all.size());
        return all;
    }

    public void batchCreate(String appToken, String tableId, List<ObjectNode> fieldRows) {
        if (fieldRows == null || fieldRows.isEmpty()) {
            return;
        }
        // 飞书单次建议不超过 500，这里按 50 批
        for (int i = 0; i < fieldRows.size(); i += 50) {
            List<ObjectNode> slice = fieldRows.subList(i, Math.min(i + 50, fieldRows.size()));
            ObjectNode body = objectMapper.createObjectNode();
            ArrayNode records = body.putArray("records");
            for (ObjectNode fields : slice) {
                ObjectNode rec = records.addObject();
                rec.set("fields", fields);
            }
            String resp = restClient.post()
                    .uri(BATCH_CREATE_URL, appToken, tableId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + tokenService.getTenantAccessToken())
                    .body(body)
                    .retrieve()
                    .body(String.class);
            try {
                JsonNode root = objectMapper.readTree(resp);
                if (root.path("code").asInt(-1) != 0) {
                    throw new IllegalStateException("写入多维表格失败: " + resp);
                }
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("解析写入多维表格响应失败", e);
            }
        }
    }
}
