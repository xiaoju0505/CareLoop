package com.careloop.bind;

import com.careloop.care.CarePlanService;
import com.careloop.ortho.OrthoCareTemplates;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/bind")
public class BindController {

    private final BindTokenService bindTokenService;
    private final CarePlanService carePlanService;

    public BindController(BindTokenService bindTokenService,
                          CarePlanService carePlanService) {
        this.bindTokenService = bindTokenService;
        this.carePlanService = carePlanService;
    }

    @PostMapping("/issue")
    public Map<String, Object> issue(@RequestBody Map<String, String> req) {
        long patientId = Long.parseLong(req.getOrDefault("patientId", "1"));
        return bindTokenService.issueToken(patientId);
    }

    @GetMapping("/resolve/{token}")
    public Map<String, Object> resolve(@PathVariable String token) {
        Map<String, Object> row = bindTokenService.resolveToken(token);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("patientId", row.get("patientId"));
        result.put("patientName", row.get("patientName"));
        result.put("diagnosis", row.get("diagnosis"));
        result.put("doctorName", row.get("doctorName"));
        result.put("code", row.get("token"));
        result.put("bound", row.get("boundAt") != null);
        result.put("welcome", OrthoCareTemplates.welcomeMessage(
                String.valueOf(row.get("patientName")),
                String.valueOf(row.get("diagnosis"))
        ));
        return result;
    }

    /**
     * 网页登录：输入 8 位病患码 → 自动匹配绑定患者。
     */
    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> req) {
        String code = req.getOrDefault("code", req.getOrDefault("token", "")).trim();
        if (!code.matches("\\d{8}")) {
            throw new IllegalArgumentException("请输入 8 位数字病患码");
        }
        String channelUserId = "web_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Map<String, Object> bound = bindTokenService.confirmBind(code, "WEB", channelUserId);
        long patientId = ((Number) bound.get("patientId")).longValue();

        String todayExtra = null;
        try {
            todayExtra = carePlanService.onPatientBound(patientId);
        } catch (Exception ignored) {
            // ignore
        }

        String welcome = OrthoCareTemplates.welcomeMessage(
                String.valueOf(bound.get("patientName")),
                String.valueOf(bound.get("diagnosis"))
        );
        welcome = welcome + "\n\n您已绑定专属档案。请在「随访助手」中按提醒回答计划随访与日常检查。";
        if (todayExtra != null && !todayExtra.isBlank()) {
            welcome = welcome + todayExtra;
        }
        bound.put("welcome", welcome);

        List<Map<String, Object>> tasks = carePlanService.listPendingTasks(patientId);
        bound.put("tasks", tasks);
        if (!tasks.isEmpty()) {
            bound.put("pendingTaskId", tasks.get(0).get("taskId"));
            bound.put("pendingQuestion", tasks.get(0).get("question"));
        }
        return bound;
    }

    @PostMapping("/confirm")
    public Map<String, Object> confirm(@RequestBody Map<String, String> req) {
        // 兼容旧前端：走登录逻辑
        if (req.get("code") != null || (req.get("token") != null && req.get("token").matches("\\d{8}"))) {
            return login(req);
        }
        req.putIfAbsent("code", req.get("token"));
        return login(req);
    }
}
