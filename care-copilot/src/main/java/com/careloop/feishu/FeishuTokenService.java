package com.careloop.feishu;

import com.careloop.config.FeishuProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class FeishuTokenService {

    private static final Logger log = LoggerFactory.getLogger(FeishuTokenService.class);
    private static final String TOKEN_URL = "https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal";

    private final FeishuProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final AtomicReference<CachedToken> cache = new AtomicReference<>();

    public FeishuTokenService(FeishuProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.create();
    }

    public String getTenantAccessToken() {
        CachedToken cached = cache.get();
        if (cached != null && cached.expireAtEpochSecond() > Instant.now().getEpochSecond() + 60) {
            return cached.token();
        }
        synchronized (this) {
            cached = cache.get();
            if (cached != null && cached.expireAtEpochSecond() > Instant.now().getEpochSecond() + 60) {
                return cached.token();
            }
            return refreshToken();
        }
    }

    private String refreshToken() {
        if (!properties.isConfigured()) {
            throw new IllegalStateException("飞书 App ID / App Secret 未配置");
        }
        String body = restClient.post()
                .uri(TOKEN_URL)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "app_id", properties.getAppId(),
                        "app_secret", properties.getAppSecret()
                ))
                .retrieve()
                .body(String.class);

        try {
            JsonNode root = objectMapper.readTree(body);
            int code = root.path("code").asInt(-1);
            if (code != 0) {
                throw new IllegalStateException("获取 tenant_access_token 失败: " + body);
            }
            String token = root.path("tenant_access_token").asText();
            int expire = root.path("expire").asInt(7200);
            cache.set(new CachedToken(token, Instant.now().getEpochSecond() + expire));
            log.info("飞书 tenant_access_token 已刷新，有效约 {} 秒", expire);
            return token;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("解析飞书 token 响应失败", e);
        }
    }

    private record CachedToken(String token, long expireAtEpochSecond) {
    }
}
