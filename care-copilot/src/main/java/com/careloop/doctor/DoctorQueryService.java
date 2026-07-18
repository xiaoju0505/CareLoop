package com.careloop.doctor;

import com.careloop.feishu.FeishuMessageService;
import com.careloop.patient.PatientNotifyService;
import com.careloop.trend.PatientTrendService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 医生群自然语言查询演示：查某患者最近恢复情况 → 汇总随访；不足则向患者收集。
 */
@Service
public class DoctorQueryService {

    private static final Logger log = LoggerFactory.getLogger(DoctorQueryService.class);

    /** 查/看看/了解 + 姓名 + 恢复/情况/随访 等 */
    private static final Pattern RECOVERY_QUERY = Pattern.compile(
            "(?s).*(?:查(?:一下|下)?|看看|了解|汇总|汇报|怎么样|如何).*(?:恢复|情况|随访|近况|病情|术后).*"
                    + "|.*(?:恢复|情况|随访|近况).*(?:查(?:一下|下)?|看看|了解|汇总).*"
    );

    private static final Pattern NAME_PATTERNS = Pattern.compile(
            "(?:患者|病人)?\\s*([\\u4e00-\\u9fa5]{2,4})\\s*(?:患者|病人|的|最近|这几天|这些天|近期|术后|恢复|情况|随访|近况)"
                    + "|查(?:一下|下)?\\s*([\\u4e00-\\u9fa5]{2,4})"
                    + "|看看\\s*([\\u4e00-\\u9fa5]{2,4})"
    );

    private final JdbcTemplate jdbcTemplate;
    private final FeishuMessageService feishuMessageService;
    private final PatientNotifyService patientNotifyService;
    private final PatientTrendService patientTrendService;

    public DoctorQueryService(JdbcTemplate jdbcTemplate,
                              FeishuMessageService feishuMessageService,
                              PatientNotifyService patientNotifyService,
                              PatientTrendService patientTrendService) {
        this.jdbcTemplate = jdbcTemplate;
        this.feishuMessageService = feishuMessageService;
        this.patientNotifyService = patientNotifyService;
        this.patientTrendService = patientTrendService;
    }

    public boolean looksLikeRecoveryQuery(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String t = text.replace(" ", "");
        boolean intent = t.contains("恢复情况") || t.contains("随访情况") || t.contains("最近恢复")
                || t.contains("这些天") || t.contains("这几天")
                || ((t.contains("查") || t.contains("看看") || t.contains("了解") || t.contains("汇总"))
                && (t.contains("恢复") || t.contains("随访") || t.contains("近况") || t.contains("情况")));
        if (!intent && !RECOVERY_QUERY.matcher(t).find()) {
            return false;
        }
        return extractNameHint(text).isPresent() || t.contains("患者") || t.contains("病人");
    }

    /**
     * @return true 若已处理（已回群），调用方勿再走病例建档
     */
    public boolean tryHandle(String text, String chatId) {
        if (!looksLikeRecoveryQuery(text)) {
            return false;
        }
        Map<String, Object> result = answerRecoveryQuery(text);
        String reply = String.valueOf(result.getOrDefault("reply", "处理失败"));
        if (chatId != null && !chatId.isBlank()) {
            feishuMessageService.sendText("chat_id", chatId, reply);
        }
        log.info("医生查询已回复 chatId={} patientId={}", chatId, result.get("patientId"));
        return true;
    }

    public Map<String, Object> answerRecoveryQuery(String text) {
        Map<String, Object> out = new LinkedHashMap<>();
        Optional<String> nameHint = extractNameHint(text);
        if (nameHint.isEmpty()) {
            out.put("ok", false);
            out.put("reply", "请说明患者姓名，例如：帮我查一下张女士最近这些天的恢复情况");
            return out;
        }
        String name = nameHint.get();
        List<Map<String, Object>> patients = findPatientsByName(name);
        if (patients.isEmpty()) {
            out.put("ok", false);
            out.put("reply", "未找到姓名含「" + name + "」的患者。请确认建档姓名，或先在群里发送病例建档。");
            return out;
        }
        if (patients.size() > 1) {
            StringBuilder sb = new StringBuilder("找到多名匹配患者，请说得更具体（或用：查恢复 患者#ID）：\n");
            for (Map<String, Object> p : patients) {
                sb.append("- #").append(p.get("id")).append(" ")
                        .append(p.get("name")).append(" · ")
                        .append(p.get("diagnosis")).append("\n");
            }
            out.put("ok", false);
            out.put("reply", sb.toString().trim());
            out.put("candidates", patients);
            return out;
        }

        // 也支持「查恢复 患者#3」
        long patientId = ((Number) patients.get(0).get("id")).longValue();
        Matcher idm = Pattern.compile("患者\\s*#\\s*(\\d+)").matcher(text);
        if (idm.find()) {
            patientId = Long.parseLong(idm.group(1));
        }

        return summarizeAndMaybeCollect(patientId);
    }

    public Map<String, Object> summarizeAndMaybeCollect(long patientId) {
        Map<String, Object> patient = loadPatient(patientId);
        if (patient == null) {
            return Map.of("ok", false, "reply", "患者不存在 #" + patientId);
        }

        int days = 7;
        List<Map<String, Object>> logs = recentLogs(patientId, days);
        List<Map<String, Object>> tasks = recentTaskAnswers(patientId, days);
        List<Map<String, Object>> alerts = recentAlerts(patientId, days);

        int signalCount = countUsefulSignals(logs, tasks);
        StringBuilder reply = new StringBuilder();
        reply.append("【随访汇总 · ").append(patient.get("name"))
                .append(" · #").append(patientId).append("】\n");
        reply.append("诊断：").append(nullToDash(patient.get("diagnosis"))).append("\n");
        reply.append("主治：").append(nullToDash(patient.get("doctorName"))).append("\n");
        reply.append("统计窗口：近 ").append(days).append(" 天\n\n");

        reply.append(patientTrendService.renderTrendMarkdown(patientId, days)).append("\n");

        if (!alerts.isEmpty()) {
            reply.append("一、告警\n");
            for (Map<String, Object> a : alerts) {
                reply.append("- [").append(a.get("createdAt")).append("] ")
                        .append(a.get("level")).append(" / ").append(a.get("status"))
                        .append("：").append(trim(String.valueOf(a.get("reason")), 120)).append("\n");
            }
            reply.append("\n");
        } else {
            reply.append("一、告警：近 ").append(days).append(" 天无红黄告警\n\n");
        }

        reply.append("二、随访节点回复\n");
        if (tasks.isEmpty()) {
            reply.append("- 暂无已回答的随访节点\n");
        } else {
            for (Map<String, Object> t : tasks) {
                reply.append("- D").append(t.get("dayOffset"))
                        .append("（").append(t.get("status")).append("）")
                        .append(trim(String.valueOf(t.get("question")), 40)).append("\n");
                if (t.get("answer") != null && !String.valueOf(t.get("answer")).isBlank()) {
                    reply.append("  患者：").append(trim(String.valueOf(t.get("answer")), 160)).append("\n");
                }
            }
        }
        reply.append("\n三、近期对话摘录\n");
        if (logs.isEmpty()) {
            reply.append("- 暂无院外对话记录\n");
        } else {
            int n = 0;
            for (Map<String, Object> logRow : logs) {
                if (n++ >= 8) {
                    break;
                }
                reply.append("- [").append(logRow.get("createdAt")).append("] ")
                        .append(logRow.get("direction")).append(" ")
                        .append(trim(String.valueOf(logRow.get("content")), 140)).append("\n");
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("patientId", patientId);
        out.put("patientName", patient.get("name"));
        out.put("signalCount", signalCount);

        if (signalCount < 2) {
            String ask = "医生想了解您近几天的恢复情况，请按三点简单回复：\n"
                    + "①伤口（干燥/渗液/红肿）\n"
                    + "②疼痛0-10分与睡眠\n"
                    + "③抗凝/止痛是否按时；有无发热或单侧小腿胀痛\n"
                    + "有不适请具体说明。";
            boolean sent = patientNotifyService.notifyPatient(patientId, ask);
            reply.append("\n四、数据不足 → 已向患者发起收集\n");
            reply.append(sent
                    ? "- 已飞书提醒并附随访页链接，患者打开网页回复后可再说「查一下"
                    + patient.get("name") + "恢复情况」刷新汇总。"
                    : "- 暂无随访页链接；请重新确认出院出码，或让患者打开已有随访页回复。");
            out.put("collected", true);
            out.put("webNotified", sent);
        } else {
            reply.append("\n四、小结\n");
            reply.append(buildBriefConclusion(alerts, tasks, logs));
            out.put("collected", false);
        }

        String text = reply.toString();
        if (text.length() > 3500) {
            text = text.substring(0, 3500) + "\n…(已截断)";
        }
        out.put("reply", text);
        return out;
    }

    private Optional<String> extractNameHint(String text) {
        if (text == null) {
            return Optional.empty();
        }
        // 患者#3 优先
        Matcher idOnly = Pattern.compile("患者\\s*#\\s*(\\d+)").matcher(text);
        if (idOnly.find()) {
            Long id = Long.parseLong(idOnly.group(1));
            Map<String, Object> p = loadPatient(id);
            if (p != null) {
                return Optional.of(String.valueOf(p.get("name")));
            }
        }
        Matcher m = NAME_PATTERNS.matcher(text.replace(" ", ""));
        if (m.find()) {
            for (int i = 1; i <= m.groupCount(); i++) {
                if (m.group(i) != null && !m.group(i).isBlank()) {
                    String n = m.group(i);
                    if (!isStopName(n)) {
                        return Optional.of(n);
                    }
                }
            }
        }
        // 再试：从库中所有活跃患者姓名做包含匹配（演示友好）
        List<String> names = jdbcTemplate.query(
                "SELECT name FROM patient WHERE status = 'ACTIVE' ORDER BY id DESC LIMIT 50",
                (rs, i) -> rs.getString(1)
        );
        String compact = text.replace(" ", "");
        for (String n : names) {
            if (n != null && n.length() >= 2 && compact.contains(n)) {
                return Optional.of(n);
            }
        }
        return Optional.empty();
    }

    private boolean isStopName(String n) {
        return List.of("患者", "病人", "最近", "这些", "这几", "恢复", "情况", "随访",
                "医生", "帮我", "一下", "怎么", "如何").contains(n);
    }

    private List<Map<String, Object>> findPatientsByName(String name) {
        return jdbcTemplate.query(
                """
                        SELECT id, name, diagnosis, doctor_name
                        FROM patient
                        WHERE name LIKE CONCAT('%', ?, '%')
                        ORDER BY id DESC
                        LIMIT 8
                        """,
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("name", rs.getString("name"));
                    m.put("diagnosis", rs.getString("diagnosis"));
                    m.put("doctorName", rs.getString("doctor_name"));
                    return m;
                },
                name
        );
    }

    private Map<String, Object> loadPatient(long patientId) {
        return jdbcTemplate.query(
                "SELECT id, name, diagnosis, doctor_name FROM patient WHERE id = ?",
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getLong("id"));
                    m.put("name", rs.getString("name"));
                    m.put("diagnosis", rs.getString("diagnosis"));
                    m.put("doctorName", rs.getString("doctor_name"));
                    return m;
                },
                patientId
        );
    }

    private List<Map<String, Object>> recentLogs(long patientId, int days) {
        return jdbcTemplate.query(
                """
                        SELECT direction, content, created_at
                        FROM encounter_log
                        WHERE patient_id = ? AND created_at >= DATE_SUB(NOW(), INTERVAL ? DAY)
                        ORDER BY id DESC
                        LIMIT 30
                        """,
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("direction", rs.getString("direction"));
                    m.put("content", rs.getString("content"));
                    m.put("createdAt", rs.getString("created_at"));
                    return m;
                },
                patientId, days
        );
    }

    private List<Map<String, Object>> recentTaskAnswers(long patientId, int days) {
        return jdbcTemplate.query(
                """
                        SELECT day_offset, question_text, answer_text, status, scheduled_at, answered_at
                        FROM followup_task
                        WHERE patient_id = ?
                          AND (answered_at >= DATE_SUB(NOW(), INTERVAL ? DAY)
                               OR scheduled_at >= DATE_SUB(NOW(), INTERVAL ? DAY))
                        ORDER BY day_offset ASC, id ASC
                        LIMIT 20
                        """,
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("dayOffset", rs.getInt("day_offset"));
                    m.put("question", rs.getString("question_text"));
                    m.put("answer", rs.getString("answer_text"));
                    m.put("status", rs.getString("status"));
                    m.put("scheduledAt", rs.getString("scheduled_at"));
                    m.put("answeredAt", rs.getString("answered_at"));
                    return m;
                },
                patientId, days, days
        );
    }

    private List<Map<String, Object>> recentAlerts(long patientId, int days) {
        return jdbcTemplate.query(
                """
                        SELECT level, reason, status, created_at
                        FROM alert_event
                        WHERE patient_id = ? AND created_at >= DATE_SUB(NOW(), INTERVAL ? DAY)
                        ORDER BY id DESC
                        LIMIT 10
                        """,
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("level", rs.getString("level"));
                    m.put("reason", rs.getString("reason"));
                    m.put("status", rs.getString("status"));
                    m.put("createdAt", rs.getString("created_at"));
                    return m;
                },
                patientId, days
        );
    }

    private int countUsefulSignals(List<Map<String, Object>> logs, List<Map<String, Object>> tasks) {
        int n = 0;
        for (Map<String, Object> t : tasks) {
            if (t.get("answer") != null && !String.valueOf(t.get("answer")).isBlank()) {
                n++;
            }
        }
        for (Map<String, Object> logRow : logs) {
            if ("IN".equals(logRow.get("direction"))) {
                String c = String.valueOf(logRow.get("content"));
                if (!c.startsWith("[系统]") && c.length() > 4) {
                    n++;
                }
            }
        }
        return n;
    }

    private String buildBriefConclusion(List<Map<String, Object>> alerts,
                                        List<Map<String, Object>> tasks,
                                        List<Map<String, Object>> logs) {
        boolean hasRed = alerts.stream().anyMatch(a -> "RED".equalsIgnoreCase(String.valueOf(a.get("level"))));
        boolean hasYellow = alerts.stream().anyMatch(a -> "YELLOW".equalsIgnoreCase(String.valueOf(a.get("level")))
                || "CONSULT".equalsIgnoreCase(String.valueOf(a.get("level"))));
        if (hasRed) {
            return "- 近窗内出现过红色急症信号，建议优先查看告警详情并必要时提前复诊。";
        }
        if (hasYellow) {
            return "- 近窗内有黄色/咨询类信号，建议结合患者原文关注伤口、疼痛或血栓相关描述。";
        }
        long answered = tasks.stream()
                .filter(t -> t.get("answer") != null && !String.valueOf(t.get("answer")).isBlank())
                .count();
        if (answered > 0) {
            return "- 已有 " + answered + " 条节点回复，未见红警；可按摘录继续追问伤口与疼痛评分。";
        }
        return "- 有对话记录但结构化节点回复偏少，建议让患者补充伤口/疼痛/用药三点。";
    }

    private String trim(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private String nullToDash(Object o) {
        if (o == null) {
            return "-";
        }
        String s = String.valueOf(o);
        return s.isBlank() ? "-" : s;
    }
}
