package com.careloop.patient;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 患者上下文：严格按 patientId 取数，禁止「取最近一个患者」。
 */
@Service
public class PatientContextService {

    private final JdbcTemplate jdbcTemplate;

    public PatientContextService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<Map<String, Object>> loadById(long patientId) {
        if (patientId <= 0) {
            return Optional.empty();
        }
        Map<String, Object> row = jdbcTemplate.query(
                """
                        SELECT id, name, gender, diagnosis, doctor_name, case_notes, status, discharge_at
                        FROM patient WHERE id = ?
                        """,
                rs -> rs.next() ? mapPatient(rs) : null,
                patientId
        );
        return Optional.ofNullable(row);
    }

    public Optional<Long> activePlanId(long patientId) {
        Long id = jdbcTemplate.query(
                """
                        SELECT id FROM care_plan
                        WHERE patient_id = ? AND status IN ('DRAFT', 'ACTIVE')
                        ORDER BY FIELD(status,'ACTIVE','DRAFT'), id DESC
                        LIMIT 1
                        """,
                rs -> rs.next() ? rs.getLong(1) : null,
                patientId
        );
        return Optional.ofNullable(id);
    }

    private Map<String, Object> mapPatient(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rs.getLong("id"));
        m.put("patientId", rs.getLong("id"));
        m.put("name", rs.getString("name"));
        m.put("gender", rs.getString("gender"));
        m.put("diagnosis", rs.getString("diagnosis"));
        m.put("doctorName", rs.getString("doctor_name"));
        m.put("caseNotes", rs.getString("case_notes"));
        m.put("status", rs.getString("status"));
        m.put("dischargeAt", rs.getString("discharge_at"));
        return m;
    }
}
