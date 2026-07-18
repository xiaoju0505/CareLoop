package com.careloop.doctor;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 演示 API：不依赖飞书事件时也可测「查恢复情况」。
 */
@RestController
@RequestMapping("/api/demo")
public class DoctorQueryController {

    private final DoctorQueryService doctorQueryService;

    public DoctorQueryController(DoctorQueryService doctorQueryService) {
        this.doctorQueryService = doctorQueryService;
    }

    @PostMapping("/doctor-query")
    public Map<String, Object> doctorQuery(@RequestBody Map<String, String> body) {
        String text = body.getOrDefault("text", "");
        String chatId = body.get("chatId");
        if (chatId != null && !chatId.isBlank()) {
            boolean handled = doctorQueryService.tryHandle(text, chatId);
            return Map.of("handled", handled, "text", text);
        }
        return doctorQueryService.answerRecoveryQuery(text);
    }
}
