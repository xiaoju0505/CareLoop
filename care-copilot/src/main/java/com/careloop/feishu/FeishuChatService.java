package com.careloop.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class FeishuChatService {

    private static final String CHATS_URL = "https://open.feishu.cn/open-apis/im/v1/chats?page_size=50";

    private final FeishuTokenService tokenService;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public FeishuChatService(FeishuTokenService tokenService, ObjectMapper objectMapper) {
        this.tokenService = tokenService;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.create();
    }

    public List<Map<String, String>> listBotChats() {
        String token = tokenService.getTenantAccessToken();
        String body = restClient.get()
                .uri(CHATS_URL)
                .header("Authorization", "Bearer " + token)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(String.class);

        try {
            JsonNode root = objectMapper.readTree(body);
            if (root.path("code").asInt(-1) != 0) {
                throw new IllegalStateException("获取群列表失败: " + body);
            }
            List<Map<String, String>> list = new ArrayList<>();
            for (JsonNode item : root.path("data").path("items")) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("chatId", item.path("chat_id").asText());
                row.put("name", item.path("name").asText());
                row.put("chatMode", item.path("chat_mode").asText());
                list.add(row);
            }
            return list;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("解析群列表失败", e);
        }
    }
}
