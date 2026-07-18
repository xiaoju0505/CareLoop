package com.careloop.schedule;

import com.careloop.briefing.BriefingService;
import com.careloop.care.CarePlanService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class CareLoopScheduler {

    private static final Logger log = LoggerFactory.getLogger(CareLoopScheduler.class);

    private final CarePlanService carePlanService;
    private final BriefingService briefingService;
    private final JdbcTemplate jdbcTemplate;

    @Value("${careloop.demo.feishu-receive-id-type:chat_id}")
    private String receiveIdType;

    @Value("${careloop.demo.feishu-receive-id:}")
    private String receiveId;

    public CareLoopScheduler(CarePlanService carePlanService,
                             BriefingService briefingService,
                             JdbcTemplate jdbcTemplate) {
        this.carePlanService = carePlanService;
        this.briefingService = briefingService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 每分钟推送到期随访问题 */
    @Scheduled(fixedDelayString = "${careloop.schedule.followup-delay-ms:60000}")
    public void pushDueFollowups() {
        try {
            List<Map<String, Object>> results = carePlanService.pushAllDueTasks();
            long pushed = results.stream().filter(r -> Boolean.TRUE.equals(r.get("pushed"))).count();
            if (pushed > 0) {
                log.info("定时随访推送完成：{} 条", pushed);
            }
        } catch (Exception e) {
            log.warn("定时随访推送失败: {}", e.getMessage());
        }
    }

    /**
     * 每小时检查：最后随访节点前 1 天自动生成诊前简报。
     */
    @Scheduled(fixedDelayString = "${careloop.schedule.briefing-delay-ms:3600000}")
    public void autoBriefings() {
        if (receiveId == null || receiveId.isBlank()) {
            return;
        }
        try {
            List<Long> patientIds = jdbcTemplate.query(
                    """
                            SELECT DISTINCT t.patient_id
                            FROM followup_task t
                            JOIN (
                              SELECT patient_id, MAX(scheduled_at) AS max_at
                              FROM followup_task
                              GROUP BY patient_id
                            ) m ON m.patient_id = t.patient_id AND m.max_at = t.scheduled_at
                            WHERE t.scheduled_at BETWEEN NOW() AND DATE_ADD(NOW(), INTERVAL 1 DAY)
                              AND NOT EXISTS (
                                SELECT 1 FROM previsit_briefing b
                                WHERE b.patient_id = t.patient_id
                                  AND b.created_at >= DATE_SUB(NOW(), INTERVAL 2 DAY)
                              )
                            """,
                    (rs, i) -> rs.getLong(1)
            );
            for (Long pid : patientIds) {
                try {
                    Map<String, Object> r = briefingService.generateAndSend(pid, receiveIdType, receiveId);
                    log.info("自动诊前简报 patientId={} briefingId={}", pid, r.get("briefingId"));
                } catch (Exception e) {
                    log.warn("自动简报失败 patientId={}: {}", pid, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("自动简报调度失败: {}", e.getMessage());
        }
    }
}
