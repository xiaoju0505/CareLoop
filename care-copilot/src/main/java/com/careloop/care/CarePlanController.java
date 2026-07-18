package com.careloop.care;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/care-plans")
public class CarePlanController {

    private final CarePlanService carePlanService;

    public CarePlanController(CarePlanService carePlanService) {
        this.carePlanService = carePlanService;
    }

    @GetMapping("/{planId}")
    public Map<String, Object> get(@PathVariable long planId) {
        return carePlanService.getPlan(planId);
    }

    @PutMapping("/{planId}/nodes")
    @SuppressWarnings("unchecked")
    public Map<String, Object> updateNodes(@PathVariable long planId, @RequestBody Map<String, Object> body) {
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) body.get("nodes");
        if (nodes == null || nodes.isEmpty()) {
            throw new IllegalArgumentException("nodes 不能为空");
        }
        String careTips = body.get("careTips") == null ? null : String.valueOf(body.get("careTips"));
        boolean resend = Boolean.TRUE.equals(body.get("resendCard")) || "true".equals(String.valueOf(body.get("resendCard")));
        String receiveIdType = body.get("receiveIdType") == null ? null : String.valueOf(body.get("receiveIdType"));
        String receiveId = body.get("receiveId") == null ? null : String.valueOf(body.get("receiveId"));
        return carePlanService.updateDraftNodes(planId, nodes, careTips, resend, receiveIdType, receiveId);
    }
}
