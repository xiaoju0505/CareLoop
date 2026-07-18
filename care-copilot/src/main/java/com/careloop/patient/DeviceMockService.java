package com.careloop.patient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 外接设备（智能手环）模拟数据：心率、血压、血氧、步数、睡眠、HRV、体温、压力等。
 */
@Service
public class DeviceMockService {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public DeviceMockService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> snapshotAndSave(long patientId) {
        Map<String, Object> snap = generate();
        try {
            ObjectNode json = objectMapper.valueToTree(snap);
            json.put("type", "DEVICE_BAND_MOCK");
            jdbcTemplate.update(
                    "INSERT INTO encounter_log(patient_id, direction, content, structured_json) VALUES (?, 'IN', ?, ?)",
                    patientId,
                    "[手环模拟] 心率" + snap.get("heartRate") + " 血压" + snap.get("bloodPressure")
                            + " 血氧" + snap.get("spo2") + "%",
                    json.toString()
            );
        } catch (Exception e) {
            jdbcTemplate.update(
                    "INSERT INTO encounter_log(patient_id, direction, content) VALUES (?, 'IN', ?)",
                    patientId,
                    "[手环模拟] 心率" + snap.get("heartRate") + " 血压" + snap.get("bloodPressure")
            );
        }
        return snap;
    }

    public Map<String, Object> latest(long patientId) {
        Map<String, Object> found = findLatest(patientId);
        return found != null ? found : generate();
    }

    /** 仅返回真实同步过的手环快照；无记录时返回 null（分诊勿用随机值） */
    public Map<String, Object> findLatest(long patientId) {
        String structured = jdbcTemplate.query(
                """
                        SELECT structured_json FROM encounter_log
                        WHERE patient_id = ? AND content LIKE '[手环模拟]%'
                        ORDER BY id DESC LIMIT 1
                        """,
                rs -> rs.next() ? rs.getString(1) : null,
                patientId
        );
        if (structured != null && !structured.isBlank()) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = objectMapper.readValue(structured, Map.class);
                return m;
            } catch (Exception ignored) {
                // fall through
            }
        }
        return null;
    }

    public Map<String, Object> generate() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int hr = r.nextInt(62, 96);
        int sys = r.nextInt(108, 138);
        int dia = r.nextInt(68, 88);
        int spo2 = r.nextInt(95, 100);
        int steps = r.nextInt(1200, 9500);
        double sleepH = Math.round((r.nextDouble(5.5, 8.6)) * 10) / 10.0;
        int deepMin = r.nextInt(60, 140);
        int lightMin = r.nextInt(150, 280);
        int remMin = r.nextInt(60, 120);
        int hrv = r.nextInt(28, 72);
        double temp = Math.round((r.nextDouble(36.2, 37.1)) * 10) / 10.0;
        int stress = r.nextInt(18, 72);
        int calories = r.nextInt(900, 2200);
        int restingHr = r.nextInt(55, 72);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("connected", true);
        m.put("deviceName", "模拟健康手环 Pro");
        m.put("syncedAt", LocalDateTime.now().format(FMT));
        m.put("heartRate", hr);
        m.put("restingHeartRate", restingHr);
        m.put("bloodPressure", sys + "/" + dia);
        m.put("systolic", sys);
        m.put("diastolic", dia);
        m.put("spo2", spo2);
        m.put("steps", steps);
        m.put("calories", calories);
        m.put("sleepHours", sleepH);
        m.put("sleepDeepMin", deepMin);
        m.put("sleepLightMin", lightMin);
        m.put("sleepRemMin", remMin);
        m.put("hrvRmssd", hrv);
        m.put("skinTemp", temp);
        m.put("stressScore", stress);
        m.put("note", "演示数据：模拟蓝牙手环同步，非正式医疗测量");
        return m;
    }
}
