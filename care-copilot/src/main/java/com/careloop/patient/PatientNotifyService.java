package com.careloop.patient;

import com.careloop.feishu.FeishuMessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 患者触达：飞书提醒 + 随访网页链接（患者打开 /p/{token} 填写）。
 */
@Service
public class PatientNotifyService {

    private static final Logger log = LoggerFactory.getLogger(PatientNotifyService.class);

    private final FeishuMessageService feishuMessageService;
    private final JdbcTemplate jdbcTemplate;

    @Value("${careloop.public-base-url:https://fromfreedom.top}")
    private String publicBaseUrl;

    @Value("${careloop.demo.feishu-receive-id-type:chat_id}")
    private String feishuReceiveIdType;

    @Value("${careloop.demo.feishu-receive-id:}")
    private String feishuReceiveId;

    public PatientNotifyService(FeishuMessageService feishuMessageService,
                                JdbcTemplate jdbcTemplate) {
        this.feishuMessageService = feishuMessageService;
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean notifyPatient(long patientId, String message) {
        String h5 = resolveH5(patientId);
        String full = message;
        if (h5 != null) {
            full = message + "\n\n请打开随访页回复：\n" + h5;
        }

        jdbcTemplate.update(
                "INSERT INTO encounter_log(patient_id, direction, content) VALUES (?, 'OUT', ?)",
                patientId,
                "[通知-网页+飞书] " + full
        );

        if (feishuReceiveId != null && !feishuReceiveId.isBlank()) {
            try {
                String tip = "【随访提醒 · 请患者打开网页】患者#" + patientId + "\n"
                        + message + "\n"
                        + (h5 != null ? ("随访页（可转发）：" + h5) : "暂无随访页，请重新确认出院出码");
                feishuMessageService.sendText(feishuReceiveIdType, feishuReceiveId, tip);
            } catch (Exception e) {
                log.warn("飞书随访提醒失败: {}", e.getMessage());
            }
        }

        log.info("患者触达 patientId={} h5={}", patientId, h5);
        return h5 != null;
    }

    private String resolveH5(long patientId) {
        String token = jdbcTemplate.query(
                """
                        SELECT bind_token FROM patient_binding
                        WHERE patient_id = ?
                        ORDER BY CASE WHEN bound_at IS NOT NULL THEN 0 ELSE 1 END, id DESC
                        LIMIT 1
                        """,
                rs -> rs.next() ? rs.getString(1) : null,
                patientId
        );
        String base = publicBaseUrl.endsWith("/")
                ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1)
                : publicBaseUrl;
        if (token == null || token.isBlank()) {
            return base + "/patient";
        }
        return base + "/patient （病患码：" + token + "）";
    }
}
