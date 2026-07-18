package com.careloop.patient;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PatientService {

    private final JdbcTemplate jdbcTemplate;

    public PatientService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Map<String, Object>> list(int limit) {
        int lim = Math.min(Math.max(limit, 1), 100);
        return jdbcTemplate.query(
                """
                        SELECT id, name, gender, phone_mask, diagnosis, doctor_name, discharge_at, status, case_notes, created_at
                        FROM patient
                        ORDER BY id DESC
                        LIMIT ?
                        """,
                (rs, i) -> mapRow(rs),
                lim
        );
    }

    public Map<String, Object> get(long id) {
        Map<String, Object> row = jdbcTemplate.query(
                """
                        SELECT id, name, gender, phone_mask, diagnosis, doctor_name, discharge_at, status, case_notes, created_at
                        FROM patient WHERE id = ?
                        """,
                rs -> rs.next() ? mapRow(rs) : null,
                id
        );
        if (row == null) {
            throw new IllegalArgumentException("患者不存在: " + id);
        }
        return row;
    }

    @Transactional
    public Map<String, Object> create(Map<String, String> req) {
        String name = required(req, "name");
        String diagnosis = required(req, "diagnosis");
        String doctorName = req.getOrDefault("doctorName", "主管医生");
        String gender = req.getOrDefault("gender", "");
        String phone = req.getOrDefault("phoneMask", "");
        String caseNotes = req.getOrDefault("caseNotes", "");
        String dischargeAt = req.get("dischargeAt");

        jdbcTemplate.update(
                """
                        INSERT INTO patient(name, gender, phone_mask, diagnosis, doctor_name, discharge_at, status, case_notes)
                        VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE', ?)
                        """,
                name, emptyToNull(gender), emptyToNull(phone), diagnosis, doctorName,
                emptyToNull(dischargeAt), emptyToNull(caseNotes)
        );
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return get(id);
    }

    @Transactional
    public Map<String, Object> update(long id, Map<String, String> req) {
        get(id);
        jdbcTemplate.update(
                """
                        UPDATE patient
                        SET name = COALESCE(?, name),
                            gender = COALESCE(?, gender),
                            phone_mask = COALESCE(?, phone_mask),
                            diagnosis = COALESCE(?, diagnosis),
                            doctor_name = COALESCE(?, doctor_name),
                            discharge_at = COALESCE(?, discharge_at),
                            case_notes = COALESCE(?, case_notes),
                            updated_at = NOW()
                        WHERE id = ?
                        """,
                emptyToNull(req.get("name")),
                emptyToNull(req.get("gender")),
                emptyToNull(req.get("phoneMask")),
                emptyToNull(req.get("diagnosis")),
                emptyToNull(req.get("doctorName")),
                emptyToNull(req.get("dischargeAt")),
                emptyToNull(req.get("caseNotes")),
                id
        );
        return get(id);
    }

    private Map<String, Object> mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getLong("id"));
        row.put("name", rs.getString("name"));
        row.put("gender", rs.getString("gender"));
        row.put("phoneMask", rs.getString("phone_mask"));
        row.put("diagnosis", rs.getString("diagnosis"));
        row.put("doctorName", rs.getString("doctor_name"));
        row.put("dischargeAt", rs.getString("discharge_at"));
        row.put("status", rs.getString("status"));
        try {
            row.put("caseNotes", rs.getString("case_notes"));
        } catch (Exception e) {
            row.put("caseNotes", null);
        }
        row.put("createdAt", rs.getString("created_at"));
        return row;
    }

    private String required(Map<String, String> req, String key) {
        String v = req == null ? null : req.get(key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("缺少字段: " + key);
        }
        return v.trim();
    }

    private String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
