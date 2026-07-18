package com.careloop.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

@Service
public class FeishuImageService {

    private static final Logger log = LoggerFactory.getLogger(FeishuImageService.class);
    private static final String UPLOAD_URL = "https://open.feishu.cn/open-apis/im/v1/images";

    private final FeishuTokenService tokenService;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate = new RestTemplate();

    public FeishuImageService(FeishuTokenService tokenService, ObjectMapper objectMapper) {
        this.tokenService = tokenService;
        this.objectMapper = objectMapper;
    }

    public String uploadPng(byte[] pngBytes, String filename) {
        try {
            String name = (filename == null || filename.isBlank()) ? "qr.png" : filename;

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("image_type", "message");
            body.add("image", new ByteArrayResource(pngBytes) {
                @Override
                public String getFilename() {
                    return name;
                }
            });

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(tokenService.getTenantAccessToken());
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            ResponseEntity<String> resp = restTemplate.postForEntity(
                    UPLOAD_URL,
                    new HttpEntity<>(body, headers),
                    String.class
            );

            JsonNode root = objectMapper.readTree(resp.getBody());
            if (root.path("code").asInt(-1) != 0) {
                throw new IllegalStateException("上传飞书图片失败: " + resp.getBody());
            }
            String imageKey = root.path("data").path("image_key").asText();
            log.info("飞书图片已上传 imageKey={}", imageKey);
            return imageKey;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("上传飞书图片异常: " + e.getMessage(), e);
        }
    }
}
