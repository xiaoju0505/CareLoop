package com.careloop.bind;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class BindTokenService {

    private final JdbcTemplate jdbcTemplate;
    private final SecureRandom random = new SecureRandom();

    @Value("${careloop.public-base-url:https://fromfreedom.top}")
    private String publicBaseUrl;

    public BindTokenService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public Map<String, Object> issueToken(long patientId) {
        Map<String, Object> patient = jdbcTemplate.query(
                "SELECT id, name, diagnosis, doctor_name, status FROM patient WHERE id = ?",
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", rs.getLong("id"));
                    row.put("name", rs.getString("name"));
                    row.put("diagnosis", rs.getString("diagnosis"));
                    row.put("doctorName", rs.getString("doctor_name"));
                    row.put("status", rs.getString("status"));
                    return row;
                },
                patientId
        );
        if (patient == null) {
            throw new IllegalArgumentException("患者不存在: " + patientId);
        }

        String code = nextUniqueCode();

        jdbcTemplate.update(
                "DELETE FROM patient_binding WHERE patient_id = ? AND bound_at IS NULL",
                patientId
        );
        // 同一患者重新出码：旧码失效
        jdbcTemplate.update(
                "DELETE FROM patient_binding WHERE patient_id = ? AND channel IN ('PENDING','WEB')",
                patientId
        );
        jdbcTemplate.update(
                """
                        INSERT INTO patient_binding(patient_id, channel, channel_user_id, bind_token, bound_at)
                        VALUES (?, 'PENDING', ?, ?, NULL)
                        """,
                patientId, "pending_" + code, code
        );

        String portalUrl = trimSlash(publicBaseUrl) + "/patient";
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("patientId", patientId);
        result.put("patientName", patient.get("name"));
        result.put("diagnosis", patient.get("diagnosis"));
        result.put("token", code);
        result.put("code", code);
        result.put("state", code);
        result.put("bindUrl", portalUrl);
        result.put("portalUrl", portalUrl);
        result.put("tip", "请患者打开随访网页，输入 8 位专属病患码登录");
        return result;
    }

    public Map<String, Object> resolveToken(String token) {
        String code = normalizeCode(token);
        Map<String, Object> row = jdbcTemplate.query(
                """
                        SELECT b.id AS binding_id, b.patient_id, b.bind_token, b.bound_at, b.channel_user_id,
                               p.name, p.diagnosis, p.doctor_name
                        FROM patient_binding b
                        JOIN patient p ON p.id = b.patient_id
                        WHERE b.bind_token = ?
                        ORDER BY b.id DESC
                        LIMIT 1
                        """,
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("bindingId", rs.getLong("binding_id"));
                    m.put("patientId", rs.getLong("patient_id"));
                    m.put("token", rs.getString("bind_token"));
                    m.put("boundAt", rs.getTimestamp("bound_at"));
                    m.put("channelUserId", rs.getString("channel_user_id"));
                    m.put("patientName", rs.getString("name"));
                    m.put("diagnosis", rs.getString("diagnosis"));
                    m.put("doctorName", rs.getString("doctor_name"));
                    return m;
                },
                code
        );
        if (row == null) {
            throw new IllegalArgumentException("病患码无效或已过期，请向医护确认最新 8 位码");
        }
        return row;
    }

    @Transactional
    public Map<String, Object> confirmBind(String token, String channel, String channelUserId) {
        Map<String, Object> row = resolveToken(token);
        long bindingId = ((Number) row.get("bindingId")).longValue();
        long patientId = ((Number) row.get("patientId")).longValue();
        String code = String.valueOf(row.get("token"));

        String ch = (channel == null || channel.isBlank()) ? "WEB" : channel;
        String uid = (channelUserId == null || channelUserId.isBlank())
                ? "web_" + code
                : channelUserId;

        jdbcTemplate.update(
                """
                        UPDATE patient_binding
                        SET channel = ?, channel_user_id = ?, bound_at = NOW()
                        WHERE id = ?
                        """,
                ch, uid, bindingId
        );

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("patientId", patientId);
        result.put("patientName", row.get("patientName"));
        result.put("diagnosis", row.get("diagnosis"));
        result.put("doctorName", row.get("doctorName"));
        result.put("channel", ch);
        result.put("channelUserId", uid);
        result.put("token", code);
        result.put("code", code);
        result.put("bindUrl", trimSlash(publicBaseUrl) + "/patient");
        result.put("portalUrl", trimSlash(publicBaseUrl) + "/patient");
        result.put("bound", true);
        return result;
    }

    /** 8 位数字码：10000000–99999999 */
    private String nextUniqueCode() {
        for (int i = 0; i < 30; i++) {
            String code = String.format("%08d", 10_000_000 + random.nextInt(90_000_000));
            Integer cnt = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM patient_binding WHERE bind_token = ?",
                    Integer.class, code
            );
            if (cnt != null && cnt == 0) {
                return code;
            }
        }
        throw new IllegalStateException("无法生成唯一病患码，请重试");
    }

    private String normalizeCode(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim().replaceAll("\\s+", "");
        if (s.matches("\\d{8}")) {
            return s;
        }
        // 兼容旧 token / 链接尾段
        return s;
    }

    private String trimSlash(String url) {
        if (url == null || url.isBlank()) {
            return "https://fromfreedom.top";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
