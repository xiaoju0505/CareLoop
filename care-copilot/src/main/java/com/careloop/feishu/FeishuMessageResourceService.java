package com.careloop.feishu;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * 下载飞书消息中的文件/图片资源（需 im:resource 权限）。
 */
@Service
public class FeishuMessageResourceService {

    private static final Logger log = LoggerFactory.getLogger(FeishuMessageResourceService.class);
    private static final String RESOURCE_URL =
            "https://open.feishu.cn/open-apis/im/v1/messages/{message_id}/resources/{file_key}";

    private final FeishuTokenService tokenService;
    private final RestClient restClient;

    public FeishuMessageResourceService(FeishuTokenService tokenService) {
        this.tokenService = tokenService;
        this.restClient = RestClient.create();
    }

    public byte[] download(String messageId, String fileKey, String type) {
        if (blank(messageId) || blank(fileKey)) {
            return new byte[0];
        }
        String resourceType = blank(type) ? "file" : type;
        try {
            byte[] body = restClient.get()
                    .uri(RESOURCE_URL + "?type={type}", messageId, fileKey, resourceType)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenService.getTenantAccessToken())
                    .retrieve()
                    .body(byte[].class);
            return body == null ? new byte[0] : body;
        } catch (Exception e) {
            log.warn("下载飞书消息资源失败 messageId={} fileKey={} err={}", messageId, fileKey, e.getMessage());
            return new byte[0];
        }
    }

    private boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
