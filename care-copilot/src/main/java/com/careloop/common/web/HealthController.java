package com.careloop.common.web;

import com.careloop.config.FeishuProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class HealthController {

    private final FeishuProperties feishuProperties;

    public HealthController(FeishuProperties feishuProperties) {
        this.feishuProperties = feishuProperties;
    }

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "ok");
        result.put("service", "care-copilot");
        result.put("phase", "web-patient");
        result.put("patientChannel", "WEB");
        result.put("feishuConfigured", feishuProperties.isConfigured());
        result.put("orthoKb", "ortho-tka-postop");
        return result;
    }
}
