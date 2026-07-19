package com.careloop.llm;

import com.careloop.config.DeepseekProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * DeepSeek Chat Completions 客户端（密钥来自环境变量 DEEPSEEK_API_KEY）。
 */
@Service
public class DeepseekChatClient {

    private static final Logger log = LoggerFactory.getLogger(DeepseekChatClient.class);

    private final DeepseekProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public DeepseekChatClient(DeepseekProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.create();
    }

    public boolean isReady() {
        return properties.isConfigured();
    }

    /**
     * @return assistant 文本内容；失败返回 null
     */
    public String chat(String systemPrompt, String userPrompt) {
        if (!isReady()) {
            log.warn("DeepSeek 未配置：请设置环境变量 DEEPSEEK_API_KEY");
            return null;
        }
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", properties.getModel());
            body.put("temperature", 0.1);
            ArrayNode messages = body.putArray("messages");
            ObjectNode sys = messages.addObject();
            sys.put("role", "system");
            sys.put("content", systemPrompt);
            ObjectNode user = messages.addObject();
            user.put("role", "user");
            user.put("content", userPrompt);

            String url = properties.getBaseUrl().replaceAll("/$", "") + "/chat/completions";
            String resp = restClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(resp == null ? "{}" : resp);
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            if (content.isBlank()) {
                log.warn("DeepSeek 返回空内容: {}", resp);
                return null;
            }
            return content.trim();
        } catch (Exception e) {
            log.error("调用 DeepSeek 失败: {}", e.getMessage());
            return null;
        }
    }
}
