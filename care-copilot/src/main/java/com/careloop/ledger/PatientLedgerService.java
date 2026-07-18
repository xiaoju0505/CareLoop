package com.careloop.ledger;

import com.careloop.kb.FeishuBitableClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 患者台账：看板摘要（始终可用）+ 可选同步到飞书多维表。
 */
@Service
public class PatientLedgerService {

    private static final Logger log = LoggerFactory.getLogger(PatientLedgerService.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final JdbcTemplate jdbcTemplate;
    private final FeishuBitableClient bitableClient;
    private final ObjectMapper objectMapper;

    @Value("${careloop.ledger.bitable.enabled:false}")
    private boolean bitableEnabled;

    @Value("${careloop.kb.bitable.app-token:}")
    private String appToken;

    @Value("${careloop.ledger.bitable.table-patients:}")
    private String tablePatients;

    public PatientLedgerService(JdbcTemplate jdbcTemplate,
                                FeishuBitableClient bitableClient,
                                ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.bitableClient = bitableClient;
        this.objectMapper = objectMapper;
    }

    public List<Map<String, Object>> snapshotRows() {
        return jdbcTemplate.query(
                """
                        SELECT p.id, p.name, p.diagnosis, p.status, p.doctor_name,
                               (SELECT COUNT(*) FROM followup_task t
                                 WHERE t.patient_id = p.id AND t.status IN ('PENDING','SENT')) AS pending_tasks,
                               (SELECT COUNT(*) FROM alert_event a
                                 WHERE a.patient_id = p.id AND a.status = 'OPEN') AS open_alerts,
                               (SELECT MAX(cp.id) FROM care_plan cp
                                 WHERE cp.patient_id = p.id) AS plan_id,
                               (SELECT cp.status FROM care_plan cp
                                 WHERE cp.patient_id = p.id ORDER BY cp.id DESC LIMIT 1) AS plan_status
                        FROM patient p
                        WHERE p.status = 'ACTIVE'
                        ORDER BY open_alerts DESC, pending_tasks DESC, p.id DESC
                        LIMIT 30
                        """,
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("patientId", rs.getLong("id"));
                    m.put("name", rs.getString("name"));
                    m.put("diagnosis", rs.getString("diagnosis"));
                    m.put("status", rs.getString("status"));
                    m.put("doctorName", rs.getString("doctor_name"));
                    m.put("pendingTasks", rs.getInt("pending_tasks"));
                    m.put("openAlerts", rs.getInt("open_alerts"));
                    m.put("planId", rs.getObject("plan_id"));
                    m.put("planStatus", rs.getString("plan_status"));
                    return m;
                }
        );
    }

    public String renderDashboard() {
        List<Map<String, Object>> rows = snapshotRows();
        StringBuilder sb = new StringBuilder();
        sb.append("【CareLoop 随访看板】").append(LocalDateTime.now().format(FMT)).append("\n");
        sb.append("活跃患者 ").append(rows.size()).append(" 人\n\n");
        if (rows.isEmpty()) {
            sb.append("暂无 ACTIVE 患者。请先在群内建档确认出院。\n");
            return sb.toString();
        }
        int openAlertTotal = 0;
        int pendingTotal = 0;
        for (Map<String, Object> r : rows) {
            openAlertTotal += ((Number) r.get("openAlerts")).intValue();
            pendingTotal += ((Number) r.get("pendingTasks")).intValue();
        }
        sb.append("未处理告警合计：").append(openAlertTotal)
                .append("　待办任务合计：").append(pendingTotal).append("\n\n");
        sb.append("患者台账（前 ").append(Math.min(12, rows.size())).append("）：\n");
        int n = 0;
        for (Map<String, Object> r : rows) {
            if (n++ >= 12) {
                break;
            }
            sb.append("- #").append(r.get("patientId")).append(" ")
                    .append(r.get("name"))
                    .append(" · 告警").append(r.get("openAlerts"))
                    .append(" · 待办").append(r.get("pendingTasks"))
                    .append(" · 计划").append(r.get("planStatus") == null ? "-" : r.get("planStatus"));
            if (r.get("planId") != null) {
                sb.append("(P").append(r.get("planId")).append(")");
            }
            sb.append("\n");
        }
        sb.append("\n可说：查恢复 患者#ID　|　改计划#计划ID …\n");
        if (bitableEnabled && tablePatients != null && !tablePatients.isBlank()) {
            sb.append("多维表台账同步：已开启\n");
        } else {
            sb.append("多维表台账：未配置 table-patients（看板仍可用本地数据）\n");
        }
        return sb.toString();
    }

    public boolean looksLikeDashboard(String text) {
        if (text == null) {
            return false;
        }
        String t = text.replace(" ", "");
        return t.contains("看板") || t.contains("台账") || t.equals("@机器人看板")
                || t.contains("随访看板") || t.contains("患者看板");
    }

    /** 推送到飞书多维表（需预先建表字段：患者ID/姓名/诊断/待办数/未处理告警/计划ID/计划状态/更新时间） */
    public Map<String, Object> syncToBitable() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!bitableEnabled || appToken == null || appToken.isBlank()
                || tablePatients == null || tablePatients.isBlank()) {
            out.put("ok", false);
            out.put("message", "未开启 careloop.ledger.bitable 或未配置 table-patients");
            out.put("rows", snapshotRows().size());
            return out;
        }
        List<Map<String, Object>> rows = snapshotRows();
        List<ObjectNode> fieldRows = new ArrayList<>();
        String now = LocalDateTime.now().format(FMT);
        for (Map<String, Object> r : rows) {
            ObjectNode fields = objectMapper.createObjectNode();
            fields.put("患者ID", ((Number) r.get("patientId")).longValue());
            fields.put("姓名", String.valueOf(r.get("name")));
            fields.put("诊断", r.get("diagnosis") == null ? "" : String.valueOf(r.get("diagnosis")));
            fields.put("待办数", ((Number) r.get("pendingTasks")).intValue());
            fields.put("未处理告警", ((Number) r.get("openAlerts")).intValue());
            if (r.get("planId") != null) {
                fields.put("计划ID", ((Number) r.get("planId")).longValue());
            }
            fields.put("计划状态", r.get("planStatus") == null ? "" : String.valueOf(r.get("planStatus")));
            fields.put("更新时间", now);
            fieldRows.add(fields);
        }
        try {
            bitableClient.batchCreate(appToken, tablePatients, fieldRows);
            out.put("ok", true);
            out.put("synced", fieldRows.size());
            out.put("message", "已写入多维表台账 " + fieldRows.size() + " 行");
            log.info("患者台账同步多维表 {} 行", fieldRows.size());
        } catch (Exception e) {
            out.put("ok", false);
            out.put("message", "同步失败：" + e.getMessage());
            log.warn("患者台账同步失败", e);
        }
        return out;
    }
}
