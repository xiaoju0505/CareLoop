package com.careloop.care;

import com.careloop.briefing.BriefingService;
import com.careloop.caseintake.FeishuCaseIntakeService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/demo")
public class CareLoopDemoController {

    private final CarePlanService carePlanService;
    private final BriefingService briefingService;
    private final com.careloop.feishu.FeishuKnowledgePublisher knowledgePublisher;
    private final FeishuCaseIntakeService caseIntakeService;
    private final JdbcTemplate jdbcTemplate;

    public CareLoopDemoController(CarePlanService carePlanService,
                                  BriefingService briefingService,
                                  com.careloop.feishu.FeishuKnowledgePublisher knowledgePublisher,
                                  FeishuCaseIntakeService caseIntakeService,
                                  JdbcTemplate jdbcTemplate) {
        this.carePlanService = carePlanService;
        this.briefingService = briefingService;
        this.knowledgePublisher = knowledgePublisher;
        this.caseIntakeService = caseIntakeService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 排查：绑定 / 随访任务是否已生成 */
    @GetMapping("/patient-status")
    public Map<String, Object> patientStatus(@RequestParam long patientId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("patientId", patientId);
        Map<String, Object> patient = jdbcTemplate.query(
                "SELECT id, name, diagnosis FROM patient WHERE id = ?",
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", rs.getString("name"));
                    m.put("diagnosis", rs.getString("diagnosis"));
                    return m;
                },
                patientId
        );
        out.put("patient", patient);
        List<Map<String, Object>> binds = jdbcTemplate.query(
                """
                        SELECT channel, channel_user_id, bound_at, bind_token
                        FROM patient_binding WHERE patient_id = ? ORDER BY id DESC
                        """,
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("channel", rs.getString("channel"));
                    m.put("channelUserId", rs.getString("channel_user_id"));
                    m.put("boundAt", rs.getString("bound_at"));
                    m.put("bindToken", rs.getString("bind_token"));
                    return m;
                },
                patientId
        );
        out.put("bindings", binds);
        List<Map<String, Object>> tasks = jdbcTemplate.query(
                """
                        SELECT id, day_offset, task_kind, status, scheduled_at, sent_at,
                               LEFT(question_text, 80) AS question
                        FROM followup_task WHERE patient_id = ?
                        ORDER BY scheduled_at ASC, id ASC
                        """,
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("taskId", rs.getLong("id"));
                    m.put("day", rs.getInt("day_offset"));
                    m.put("kind", rs.getString("task_kind"));
                    m.put("status", rs.getString("status"));
                    m.put("scheduledAt", rs.getString("scheduled_at"));
                    m.put("sentAt", rs.getString("sent_at"));
                    m.put("question", rs.getString("question"));
                    return m;
                },
                patientId
        );
        out.put("tasks", tasks);
        out.put("tip", binds.stream().noneMatch(b -> b.get("boundAt") != null)
                ? "患者尚未打开随访页：请扫飞书出院网页码"
                : "已开通网页随访；到点看飞书提醒并打开/转发随访页");
        return out;
    }

    /**
     * 创建离院计划草稿，并发送飞书确认卡片。
     * {
     *   "patientId": 1,
     *   "receiveIdType": "chat_id",
     *   "receiveId": "oc_xxx"
     * }
     */
    @PostMapping("/create-care-plan")
    public Map<String, Object> createCarePlan(@RequestBody Map<String, String> req) {
        long patientId = Long.parseLong(req.getOrDefault("patientId", "1"));
        String receiveIdType = req.getOrDefault("receiveIdType", "chat_id");
        String receiveId = req.getOrDefault("receiveId", "");
        if (receiveId.isBlank()) {
            throw new IllegalArgumentException("receiveId 不能为空");
        }
        return carePlanService.createDraftAndNotifyDoctor(patientId, receiveIdType, receiveId);
    }

    /**
     * 推送下一条到期随访问题给患者（Mock：写入 encounter_log OUT）
     */
    @PostMapping("/push-followup")
    public Map<String, Object> pushFollowup(@RequestBody Map<String, String> req) {
        long patientId = Long.parseLong(req.getOrDefault("patientId", "1"));
        return carePlanService.pushNextQuestion(patientId);
    }

    /**
     * 生成诊前简报并发送到飞书
     */
    @PostMapping("/generate-briefing")
    public Map<String, Object> generateBriefing(@RequestBody Map<String, String> req) {
        long patientId = Long.parseLong(req.getOrDefault("patientId", "1"));
        String receiveIdType = req.getOrDefault("receiveIdType", "chat_id");
        String receiveId = req.getOrDefault("receiveId", "");
        if (receiveId.isBlank()) {
            throw new IllegalArgumentException("receiveId 不能为空");
        }
        return briefingService.generateAndSend(patientId, receiveIdType, receiveId);
    }

    /**
     * 发布骨科知识库到飞书群（云文档优先，失败则群内全文）。
     */
    @PostMapping("/publish-ortho-kb")
    public Map<String, Object> publishOrthoKb(@RequestBody Map<String, String> req) {
        String receiveIdType = req.getOrDefault("receiveIdType", "chat_id");
        String receiveId = req.getOrDefault("receiveId", "");
        if (receiveId.isBlank()) {
            throw new IllegalArgumentException("receiveId 不能为空");
        }
        return knowledgePublisher.publishToFeishu(receiveIdType, receiveId);
    }

    /**
     * 飞书建档演示：粘贴电子病历文本 → 识别 → 推送确认卡。
     * 正式环境由飞书群内发消息触发（需订阅 im.message.receive_v1）。
     */
    @PostMapping("/intake-case")
    public Map<String, Object> intakeCase(@RequestBody Map<String, String> req) {
        String text = req.getOrDefault("text", "");
        if (text.isBlank()) {
            throw new IllegalArgumentException("text 不能为空（请粘贴出院小结/病例文本）");
        }
        String receiveIdType = req.getOrDefault("receiveIdType", "chat_id");
        String receiveId = req.getOrDefault("receiveId", "");
        if (receiveId.isBlank()) {
            throw new IllegalArgumentException("receiveId 不能为空");
        }
        return caseIntakeService.intakeFromText(text, receiveIdType, receiveId);
    }
}
