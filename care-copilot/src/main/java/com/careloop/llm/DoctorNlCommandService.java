package com.careloop.llm;

import com.careloop.alert.AlertActionService;
import com.careloop.bind.DischargeQrService;
import com.careloop.briefing.BriefingService;
import com.careloop.care.CarePlanAdaptationService;
import com.careloop.care.CarePlanEditService;
import com.careloop.care.CarePlanService;
import com.careloop.caseintake.FeishuCaseIntakeService;
import com.careloop.doctor.DoctorQueryService;
import com.careloop.feishu.FeishuMessageService;
import com.careloop.ledger.PatientLedgerService;
import com.careloop.ortho.OrthoKnowledgeService;
import com.careloop.patient.DeviceMockService;
import com.careloop.patient.PatientNotifyService;
import com.careloop.patient.PatientService;
import com.careloop.session.FeishuDoctorSessionStore;
import com.careloop.trend.PatientTrendService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 医生自然语言代理：DeepSeek 选工具 → 执行系统已有能力。
 * 高危写操作走二次确认；清单外能力统一回复「暂时未配置相关工具」。
 */
@Service
public class DoctorNlCommandService {

    private static final Logger log = LoggerFactory.getLogger(DoctorNlCommandService.class);
    private static final String UNSUPPORTED_MSG = "暂时未配置相关工具。可发送「帮助」查看已支持能力。";

    private static final String AGENT_SYSTEM = """
            你是 CareLoop 飞书医生助手。根据医生中文指令，选择【一个】工具完成任务。
            只输出 JSON（不要 markdown）：
            {"tool":"工具名","args":{},"say":"一句话说明你理解的意图"}
            
            【规则】
            1. 只能从下方清单选 tool，禁止编造工具名。
            2. 若指令明显不在清单内（如开药、预约挂号、查医保、发邮件等），必须选 unsupported。
            3. reply 仅用于寒暄/解释；需要系统做事时禁止用 reply 搪塞。
            
            【工具清单】
            只读：
            - list_patients：查看患者列表
            - search_patients：按姓名/诊断/医生模糊搜。args: {"keyword":"..."}
            - show_dashboard：随访看板
            - get_patient：患者详情。args: patientId 或 patientName
            - show_plan：查看计划内容。args: planId，或 patientId/patientName（取最新）
            - list_draft_plans：待确认草稿计划列表
            - list_open_alerts：未处理告警
            - list_pending_tasks：某患者待办。args: patientId 或 patientName
            - query_recovery：查恢复情况
            - show_trend：近N日趋势。args: patientId/patientName, days(可选默认7)
            - device_snapshot：查/刷手环模拟数据。args: patientId/patientName, refresh(true则重新采样)
            - kb_summary：知识库摘要
            - kb_markdown：知识库全文（飞书 Markdown）
            - help：说明已支持能力
            - reply：纯文字。args: {"text":"..."}
            - unsupported：清单外能力。args 可空
            
            写入：
            - create_patient：新建患者。args: name, diagnosis, doctorName可选
            - update_patient：改档案。args: patientId/patientName + name/diagnosis/doctorName/dischargeAt/caseNotes 任选
            - restore_patient：从归档恢复。args: patientId 或 patientName
            - intake_case：病历文本建档（推确认卡）。args: {"text":"病历原文..."}
            - create_draft_plan：生成计划草稿并推确认卡。args: patientId/patientName
            - edit_plan：自然语言改计划。args: planId, text
            - push_followup：推送下一条随访。args: patientId/patientName
            - generate_briefing：诊前简报。args: patientId/patientName
            - issue_patient_code：发病人码。args: patientId/patientName
            - notify_patient：通知患者。args: patientId/patientName, message
            - reply_patient：医生回复患者（写日志，可关告警）。args: patientId/patientName, message, alertId可选
            - ack_alert / call_followup_alert / early_recheck_alert / ignore_alert：告警处置。args: alertId；ignore 可带 note
            - adapt_plan_suggest：评估并推送计划自适应建议。args: patientId/patientName
            - confirm_adapt：确认采纳自适应。args: planId, applyNl
            - sync_ledger：同步台账到多维表
            
            高危（系统会再确认；say 不要说已经做完）：
            - cancel_all_plans / cancel_patient_plan
            - confirm_plan：args planId
            - push_all_due：推送全部到期随访
            - delete_patient / delete_all_patients：删档案（≠取消计划≠列表）
            
            【易混】
            - 删除患者 → delete_*；取消计划 → cancel_*；查看列表 → list_patients
            - 禁止把「删除患者」选成 list_patients
            """;

    private final DeepseekChatClient deepseek;
    private final DoctorNlIntentService intentService;
    private final FeishuDoctorSessionStore sessionStore;
    private final FeishuMessageService feishuMessageService;
    private final PatientLedgerService patientLedgerService;
    private final DoctorQueryService doctorQueryService;
    private final PatientService patientService;
    private final CarePlanService carePlanService;
    private final CarePlanEditService carePlanEditService;
    private final CarePlanAdaptationService carePlanAdaptationService;
    private final BriefingService briefingService;
    private final DischargeQrService dischargeQrService;
    private final PatientNotifyService patientNotifyService;
    private final AlertActionService alertActionService;
    private final OrthoKnowledgeService knowledgeService;
    private final PatientTrendService patientTrendService;
    private final DeviceMockService deviceMockService;
    private final FeishuCaseIntakeService caseIntakeService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Value("${careloop.demo.feishu-receive-id-type:chat_id}")
    private String demoReceiveIdType;
    @Value("${careloop.demo.feishu-receive-id:}")
    private String demoReceiveId;

    public DoctorNlCommandService(DeepseekChatClient deepseek,
                                  DoctorNlIntentService intentService,
                                  FeishuDoctorSessionStore sessionStore,
                                  FeishuMessageService feishuMessageService,
                                  PatientLedgerService patientLedgerService,
                                  DoctorQueryService doctorQueryService,
                                  PatientService patientService,
                                  CarePlanService carePlanService,
                                  CarePlanEditService carePlanEditService,
                                  CarePlanAdaptationService carePlanAdaptationService,
                                  BriefingService briefingService,
                                  DischargeQrService dischargeQrService,
                                  PatientNotifyService patientNotifyService,
                                  AlertActionService alertActionService,
                                  OrthoKnowledgeService knowledgeService,
                                  PatientTrendService patientTrendService,
                                  DeviceMockService deviceMockService,
                                  FeishuCaseIntakeService caseIntakeService,
                                  JdbcTemplate jdbcTemplate,
                                  ObjectMapper objectMapper) {
        this.deepseek = deepseek;
        this.intentService = intentService;
        this.sessionStore = sessionStore;
        this.feishuMessageService = feishuMessageService;
        this.patientLedgerService = patientLedgerService;
        this.doctorQueryService = doctorQueryService;
        this.patientService = patientService;
        this.carePlanService = carePlanService;
        this.carePlanEditService = carePlanEditService;
        this.carePlanAdaptationService = carePlanAdaptationService;
        this.briefingService = briefingService;
        this.dischargeQrService = dischargeQrService;
        this.patientNotifyService = patientNotifyService;
        this.alertActionService = alertActionService;
        this.knowledgeService = knowledgeService;
        this.patientTrendService = patientTrendService;
        this.deviceMockService = deviceMockService;
        this.caseIntakeService = caseIntakeService;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public boolean tryHandle(String rawText, String chatId) {
        String text = stripMentions(rawText);
        if (text == null || text.isBlank()) {
            return false;
        }
        if (text.startsWith("建档|") || text.startsWith("建档｜")) {
            return false;
        }
        if (text.matches("(?s)^改计划\\s*#?\\d+[\\s\\S]*")
                || text.matches("(?s)^回复患者\\s*#?\\d+[\\s\\S]+")) {
            return false;
        }

        FeishuDoctorSessionStore.Session pending = sessionStore.get(chatId);
        if (pending != null && pending.mode() == FeishuDoctorSessionStore.Mode.CONFIRM_NL) {
            return handleConfirmSession(text, chatId, pending);
        }

        try {
            ObjectNode plan = planWithLlmOrHeuristic(text);
            String tool = plan.path("tool").asText("unsupported");
            JsonNode args = plan.has("args") ? plan.get("args") : objectMapper.createObjectNode();
            String say = plan.path("say").asText("");
            log.info("医生NL代理 chatId={} tool={} text={}", chatId, tool, text);

            String result = dispatchTool(tool, args, chatId, text);
            if ("__ALREADY_SENT__".equals(result)) {
                if (!say.isBlank()) {
                    feishuMessageService.sendText("chat_id", chatId, say);
                }
                return true;
            }
            String msg = join(say, result);
            if (msg.isBlank()) {
                msg = UNSUPPORTED_MSG;
            }
            feishuMessageService.sendText("chat_id", chatId, msg);
            return true;
        } catch (Exception e) {
            log.error("医生NL代理失败: {}", e.getMessage(), e);
            feishuMessageService.sendText("chat_id", chatId,
                    "处理失败：" + e.getMessage() + "\n可再说一次，或发送「帮助」。");
            return true;
        }
    }

    private boolean handleConfirmSession(String text, String chatId, FeishuDoctorSessionStore.Session pending) {
        ObjectNode intent = intentService.parse(text);
        String in = intent.path("intent").asText("UNKNOWN");
        boolean confirm = "CONFIRM".equals(in)
                || text.matches("(?i)^(确认|确认取消|确认删除|确认推送|是|好的|执行)$");
        boolean abort = "ABORT".equals(in) || text.contains("放弃") || text.contains("算了");
        if (!confirm && !abort && deepseek.isReady()) {
            String raw = deepseek.chat(
                    "判断用户是否确认危险操作。只输出JSON：{\"confirm\":true|false,\"abort\":true|false}",
                    text);
            try {
                JsonNode n = objectMapper.readTree(extractJson(raw == null ? "{}" : raw));
                confirm = n.path("confirm").asBoolean(false);
                abort = n.path("abort").asBoolean(false);
            } catch (Exception ignored) {
            }
        }
        if (confirm) {
            String result = executePending(pending.pendingActionJson());
            sessionStore.clear(chatId);
            feishuMessageService.sendText("chat_id", chatId, result);
            return true;
        }
        if (abort) {
            sessionStore.clear(chatId);
            feishuMessageService.sendText("chat_id", chatId, "已放弃，未做任何变更。");
            return true;
        }
        feishuMessageService.sendText("chat_id", chatId,
                "当前有待确认操作。请回复「确认」执行，或「放弃」。");
        return true;
    }

    private ObjectNode planWithLlmOrHeuristic(String text) {
        ObjectNode forced = forceDeleteOrList(text);
        if (forced != null) {
            return forced;
        }
        if (deepseek.isReady()) {
            String raw = deepseek.chat(AGENT_SYSTEM, text);
            ObjectNode parsed = parseAgentJson(raw);
            if (parsed != null && parsed.hasNonNull("tool")) {
                if (isDeletePatientsUtterance(text)
                        && "list_patients".equals(parsed.path("tool").asText())) {
                    return forceDeleteOrList(text);
                }
                return parsed;
            }
            log.warn("DeepSeek JSON 无效，启发式兜底: {}", raw);
        }
        return heuristicPlan(text);
    }

    private ObjectNode forceDeleteOrList(String text) {
        if (isDeletePatientsUtterance(text)) {
            ObjectNode p = objectMapper.createObjectNode();
            p.putObject("args");
            p.put("tool", "delete_all_patients");
            p.put("say", "按你的意思：删除患者列表中的全部患者档案（不是取消计划）。");
            return p;
        }
        return null;
    }

    private static boolean isDeletePatientsUtterance(String text) {
        String t = text.replaceAll("\\s+", "");
        boolean del = t.contains("删除") || t.contains("删掉") || t.contains("清掉") || t.contains("清除");
        boolean patients = t.contains("患者") || t.contains("病人");
        boolean all = t.contains("所有") || t.contains("全部") || t.contains("列表");
        return del && patients && (all || t.contains("列表"));
    }

    private ObjectNode parseAgentJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonNode n = objectMapper.readTree(extractJson(raw));
            ObjectNode out = objectMapper.createObjectNode();
            out.put("tool", n.path("tool").asText("unsupported"));
            out.set("args", n.has("args") && n.get("args").isObject()
                    ? n.get("args") : objectMapper.createObjectNode());
            out.put("say", n.path("say").asText(""));
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private ObjectNode heuristicPlan(String text) {
        ObjectNode p = objectMapper.createObjectNode();
        p.putObject("args");
        String t = text.replaceAll("\\s+", "");
        if (isDeletePatientsUtterance(text)) {
            p.put("tool", "delete_all_patients");
            p.put("say", "准备删除全部患者档案。");
            return p;
        }
        if ((t.contains("取消") || t.contains("停掉")) && (t.contains("所有") || t.contains("全部"))
                && (t.contains("计划") || t.contains("随访"))) {
            p.put("tool", "cancel_all_plans");
            return p;
        }
        if (t.contains("趋势")) {
            p.put("tool", "show_trend");
            fillPatientArgsFromText(p, text);
            return p;
        }
        if (t.contains("草稿") && t.contains("计划")) {
            p.put("tool", "list_draft_plans");
            return p;
        }
        if (t.contains("看板") || t.contains("台账")) {
            p.put("tool", "show_dashboard");
            return p;
        }
        if (t.contains("告警") && (t.contains("列表") || t.contains("未处理") || t.contains("查看"))) {
            p.put("tool", "list_open_alerts");
            return p;
        }
        if (t.contains("简报") && (t.contains("生成") || t.contains("出"))) {
            p.put("tool", "generate_briefing");
            fillPatientArgsFromText(p, text);
            return p;
        }
        if ((t.contains("患者") || t.contains("列表") || t.contains("还有"))
                && !t.contains("删除") && !t.contains("删掉")) {
            p.put("tool", "list_patients");
            return p;
        }
        if (t.contains("帮助") || t.contains("你能做什么")) {
            p.put("tool", "help");
            return p;
        }
        p.put("tool", "unsupported");
        return p;
    }

    private void fillPatientArgsFromText(ObjectNode p, String text) {
        var m = java.util.regex.Pattern.compile("#?(\\d+)").matcher(text);
        if (m.find()) {
            p.putObject("args").put("patientId", Long.parseLong(m.group(1)));
        }
    }

    private String dispatchTool(String tool, JsonNode args, String chatId, String originalText) {
        return switch (tool == null ? "" : tool) {
            case "list_patients" -> listPatients();
            case "search_patients" -> searchPatients(args);
            case "show_dashboard" -> patientLedgerService.renderDashboard();
            case "get_patient" -> getPatient(args);
            case "show_plan" -> showPlan(args);
            case "list_draft_plans" -> listDraftPlans();
            case "list_open_alerts" -> listOpenAlerts();
            case "list_pending_tasks" -> listPendingTasks(args);
            case "query_recovery" -> queryRecovery(args, chatId, originalText);
            case "show_trend" -> showTrend(args);
            case "device_snapshot" -> deviceSnapshot(args);
            case "kb_summary" -> String.valueOf(knowledgeService.summary());
            case "kb_markdown" -> knowledgeService.toFeishuMarkdown();
            case "help" -> helpText();
            case "reply" -> {
                String t = args.path("text").asText("").trim();
                yield t.isBlank() ? UNSUPPORTED_MSG : t;
            }
            case "unsupported" -> UNSUPPORTED_MSG;

            case "create_patient" -> createPatient(args);
            case "update_patient" -> updatePatient(args);
            case "restore_patient" -> restorePatient(args);
            case "intake_case" -> intakeCase(args, chatId);
            case "create_draft_plan" -> createDraftPlan(args, chatId);
            case "edit_plan" -> editPlan(args, chatId);
            case "push_followup" -> pushFollowup(args);
            case "generate_briefing" -> generateBriefing(args, chatId);
            case "issue_patient_code" -> issueCode(args, chatId);
            case "notify_patient" -> notifyPatient(args);
            case "reply_patient" -> replyPatient(args);
            case "ack_alert" -> alertAction("ACK_READ", args, chatId);
            case "call_followup_alert" -> alertAction("CALL_FOLLOWUP", args, chatId);
            case "early_recheck_alert" -> alertAction("BRIEF_EARLY", args, chatId);
            case "ignore_alert" -> ignoreAlert(args, chatId);
            case "adapt_plan_suggest" -> adaptSuggest(args);
            case "confirm_adapt" -> confirmAdapt(args);
            case "sync_ledger" -> String.valueOf(patientLedgerService.syncToBitable());

            case "cancel_all_plans" -> stageDanger(chatId, "CANCEL_ALL_PLANS", null,
                    "⚠️ 即将取消全部活跃/草稿【随访计划】（患者档案保留）。约 "
                            + countActivePlans(null) + " 条。\n请回复「确认」执行，或「放弃」。");
            case "cancel_patient_plan" -> stageCancelPatient(chatId, args);
            case "confirm_plan" -> stageConfirmPlan(chatId, args);
            case "push_all_due" -> stageDanger(chatId, "PUSH_ALL_DUE", null,
                    "⚠️ 即将推送全部到期随访任务。\n请回复「确认推送」或「确认」执行，或「放弃」。");
            case "delete_patient" -> stageDeletePatient(chatId, args);
            case "delete_all_patients" -> stageDanger(chatId, "DELETE_ALL_PATIENTS", null,
                    "⚠️ 即将【归档删除】全部 ACTIVE 患者档案及其计划/任务（共 "
                            + countActivePatients() + " 人）。\n可用「恢复患者」找回。\n请回复「确认删除」执行，或「放弃」。");
            default -> UNSUPPORTED_MSG;
        };
    }

    private String stageDanger(String chatId, String type, Long patientId, String prompt) {
        ObjectNode action = objectMapper.createObjectNode();
        action.put("type", type);
        if (patientId != null) {
            action.put("patientId", patientId);
        }
        sessionStore.startConfirmNl(chatId, action.toString());
        return prompt;
    }

    private String stageCancelPatient(String chatId, JsonNode args) {
        Long pid = resolvePatientId(args);
        if (pid == null) {
            return "请指明患者，例如：取消患者#12的计划";
        }
        return stageDanger(chatId, "CANCEL_PATIENT_PLAN", pid,
                "⚠️ 即将取消患者#" + pid + " 的随访计划。\n请回复「确认」或「放弃」。");
    }

    private String stageConfirmPlan(String chatId, JsonNode args) {
        long planId = resolvePlanId(args);
        if (planId <= 0) {
            return "请提供 planId，例如：确认计划#22出院；或先说「查看草稿计划」";
        }
        ObjectNode action = objectMapper.createObjectNode();
        action.put("type", "CONFIRM_PLAN");
        action.put("planId", planId);
        sessionStore.startConfirmNl(chatId, action.toString());
        return "⚠️ 即将确认计划#" + planId + " 出院并生成随访任务与病患码。\n请回复「确认」或「放弃」。";
    }

    private String stageDeletePatient(String chatId, JsonNode args) {
        Long pid = resolvePatientId(args);
        if (pid == null) {
            return "请指明要删除的患者，例如：删除患者#12";
        }
        return stageDanger(chatId, "DELETE_PATIENT", pid,
                "⚠️ 即将归档删除患者#" + pid + " 的档案及关联计划/任务。\n请回复「确认删除」或「放弃」。");
    }

    private String executePending(String actionJson) {
        try {
            JsonNode action = objectMapper.readTree(actionJson == null ? "{}" : actionJson);
            return switch (action.path("type").asText("")) {
                case "CANCEL_ALL_PLANS" -> cancelPlans(null);
                case "CANCEL_PATIENT_PLAN" -> cancelPlans(action.path("patientId").asLong(0));
                case "CONFIRM_PLAN" -> {
                    var r = carePlanService.confirmPlan(action.path("planId").asLong(0), "feishu-nl");
                    yield "已确认出院计划：" + r;
                }
                case "PUSH_ALL_DUE" -> "批量推送结果：" + carePlanService.pushAllDueTasks();
                case "DELETE_PATIENT" -> archivePatients(action.path("patientId").asLong(0));
                case "DELETE_ALL_PATIENTS" -> archivePatients(null);
                default -> "未知待确认操作，未执行。";
            };
        } catch (Exception e) {
            return "执行失败：" + e.getMessage();
        }
    }

    private String archivePatients(Long patientId) {
        List<Map<String, Object>> rows;
        if (patientId == null) {
            rows = jdbcTemplate.queryForList("SELECT id FROM patient WHERE status = 'ACTIVE'");
        } else {
            rows = jdbcTemplate.queryForList(
                    "SELECT id FROM patient WHERE id = ? AND status = 'ACTIVE'", patientId);
        }
        if (rows.isEmpty()) {
            return "没有可删除的 ACTIVE 患者。";
        }
        int n = 0;
        for (Map<String, Object> r : rows) {
            long pid = ((Number) r.get("id")).longValue();
            jdbcTemplate.update(
                    "UPDATE care_plan SET status='CANCELLED', updated_at=NOW() WHERE patient_id=? AND status IN ('ACTIVE','DRAFT')",
                    pid);
            jdbcTemplate.update(
                    "UPDATE followup_task SET status='CANCELLED' WHERE patient_id=? AND status IN ('PENDING','SENT')",
                    pid);
            n += jdbcTemplate.update(
                    "UPDATE patient SET status='ARCHIVED', updated_at=NOW() WHERE id=? AND status='ACTIVE'",
                    pid);
        }
        return "已归档删除患者 " + n + " 人（计划与未完成任务已停止）。"
                + (patientId == null ? "（全部）" : "（患者#" + patientId + "）")
                + "\n需要时可说：恢复患者#ID";
    }

    private String cancelPlans(Long patientId) {
        if (patientId != null && patientId <= 0) {
            return "患者 ID 无效。";
        }
        List<Map<String, Object>> plans;
        if (patientId == null) {
            plans = jdbcTemplate.queryForList(
                    "SELECT id, patient_id FROM care_plan WHERE status IN ('ACTIVE','DRAFT')");
        } else {
            plans = jdbcTemplate.queryForList(
                    "SELECT id, patient_id FROM care_plan WHERE patient_id=? AND status IN ('ACTIVE','DRAFT')",
                    patientId);
        }
        if (plans.isEmpty()) {
            return "没有可取消的计划。";
        }
        int planN = 0, taskN = 0;
        for (Map<String, Object> p : plans) {
            long planId = ((Number) p.get("id")).longValue();
            long pid = ((Number) p.get("patient_id")).longValue();
            planN += jdbcTemplate.update(
                    "UPDATE care_plan SET status='CANCELLED', updated_at=NOW() WHERE id=? AND status IN ('ACTIVE','DRAFT')",
                    planId);
            taskN += jdbcTemplate.update(
                    "UPDATE followup_task SET status='CANCELLED' WHERE patient_id=? AND plan_id=? AND status IN ('PENDING','SENT')",
                    pid, planId);
        }
        return "已取消计划 " + planN + " 条，停止任务 " + taskN + " 条。"
                + (patientId == null ? "（全部）" : "（患者#" + patientId + "）");
    }

    private String listPatients() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                        SELECT p.id, p.name, p.diagnosis,
                               (SELECT cp.status FROM care_plan cp WHERE cp.patient_id=p.id ORDER BY cp.id DESC LIMIT 1) AS plan_status,
                               (SELECT cp.id FROM care_plan cp WHERE cp.patient_id=p.id ORDER BY cp.id DESC LIMIT 1) AS plan_id,
                               (SELECT COUNT(*) FROM followup_task t WHERE t.patient_id=p.id AND t.status IN ('PENDING','SENT')) AS pending_tasks
                        FROM patient p WHERE p.status='ACTIVE' ORDER BY p.id DESC LIMIT 40
                        """);
        StringBuilder sb = new StringBuilder("【当前患者列表】共 ").append(rows.size()).append(" 人\n\n");
        if (rows.isEmpty()) {
            return sb.append("暂无 ACTIVE 患者。").toString();
        }
        for (Map<String, Object> r : rows) {
            sb.append("#").append(r.get("id")).append(" ")
                    .append(nullToDash(r.get("name"))).append("｜")
                    .append(nullToDash(r.get("diagnosis"))).append("｜计划 ")
                    .append(nullToDash(r.get("plan_status")))
                    .append(r.get("plan_id") == null ? "" : ("(#" + r.get("plan_id") + ")"))
                    .append("｜待办 ").append(r.get("pending_tasks")).append("\n");
        }
        return sb.toString();
    }

    private String searchPatients(JsonNode args) {
        String kw = args.path("keyword").asText("").trim();
        if (kw.isBlank()) {
            return "请提供搜索关键词，例如：搜索膝关节患者";
        }
        String like = "%" + kw + "%";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                        SELECT id, name, diagnosis, doctor_name, status FROM patient
                        WHERE name LIKE ? OR diagnosis LIKE ? OR doctor_name LIKE ?
                        ORDER BY FIELD(status,'ACTIVE','ARCHIVED'), id DESC LIMIT 20
                        """,
                like, like, like);
        if (rows.isEmpty()) {
            return "未找到匹配「" + kw + "」的患者。";
        }
        StringBuilder sb = new StringBuilder("【搜索】").append(kw).append(" → ").append(rows.size()).append(" 人\n");
        for (Map<String, Object> r : rows) {
            sb.append("#").append(r.get("id")).append(" ")
                    .append(nullToDash(r.get("name"))).append("｜")
                    .append(nullToDash(r.get("diagnosis"))).append("｜")
                    .append(nullToDash(r.get("doctor_name"))).append("｜")
                    .append(nullToDash(r.get("status"))).append("\n");
        }
        return sb.toString();
    }

    private String getPatient(JsonNode args) {
        Long pid = resolvePatientId(args);
        if (pid == null) {
            pid = resolvePatientIdIncludingArchived(args);
        }
        if (pid == null) {
            return "请指定患者，例如：查看患者#12";
        }
        Map<String, Object> p = patientService.get(pid);
        return "患者#" + pid + " " + p.get("name")
                + "\n诊断：" + p.get("diagnosis")
                + "\n医生：" + p.get("doctorName")
                + "\n状态：" + p.get("status")
                + "\n出院日：" + p.get("dischargeAt")
                + "\n病历备注：" + nullToDash(p.get("caseNotes"));
    }

    private String showPlan(JsonNode args) {
        long planId = resolvePlanId(args);
        if (planId <= 0) {
            Long pid = resolvePatientId(args);
            if (pid != null) {
                Long latest = jdbcTemplate.query(
                        "SELECT id FROM care_plan WHERE patient_id=? ORDER BY id DESC LIMIT 1",
                        rs -> rs.next() ? rs.getLong(1) : null, pid);
                planId = latest == null ? 0 : latest;
            }
        }
        if (planId <= 0) {
            return "请指定计划#ID 或患者，例如：查看计划#22 / 查看马卫国的计划";
        }
        Map<String, Object> plan = carePlanService.getPlan(planId);
        StringBuilder sb = new StringBuilder();
        sb.append("【计划#").append(planId).append("】状态 ").append(plan.get("status"))
                .append("｜患者#").append(plan.get("patientId"))
                .append("｜").append(nullToDash(plan.get("title"))).append("\n");
        try {
            JsonNode content = plan.get("content") instanceof JsonNode j
                    ? j : objectMapper.readTree(String.valueOf(plan.get("contentJson")));
            sb.append("随访天数：").append(content.path("followupDays").asText("—")).append("\n");
            sb.append("每日采集：").append(content.path("dailyCollectHour").asText("20"))
                    .append(":").append(String.format("%02d", content.path("dailyCollectMinute").asInt(0))).append("\n");
            if (content.has("careTips") && !content.path("careTips").asText("").isBlank()) {
                sb.append("叮嘱：").append(content.path("careTips").asText()).append("\n");
            }
            JsonNode nodes = content.path("nodes");
            if (nodes.isArray() && !nodes.isEmpty()) {
                sb.append("节点：\n");
                int i = 0;
                for (JsonNode n : nodes) {
                    if (i++ >= 12) {
                        sb.append("…共 ").append(nodes.size()).append(" 个节点\n");
                        break;
                    }
                    sb.append("- D").append(n.path("day").asText("?"))
                            .append(" ").append(n.path("question").asText("")).append("\n");
                }
            }
        } catch (Exception e) {
            sb.append("内容解析失败：").append(e.getMessage());
        }
        return sb.toString();
    }

    private String listDraftPlans() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                        SELECT cp.id, cp.patient_id, cp.title, p.name
                        FROM care_plan cp LEFT JOIN patient p ON p.id=cp.patient_id
                        WHERE cp.status='DRAFT' ORDER BY cp.id DESC LIMIT 20
                        """);
        if (rows.isEmpty()) {
            return "当前没有待确认的草稿计划。";
        }
        StringBuilder sb = new StringBuilder("【草稿计划】").append(rows.size()).append(" 条\n");
        for (Map<String, Object> r : rows) {
            sb.append("计划#").append(r.get("id"))
                    .append(" 患者#").append(r.get("patient_id")).append(" ")
                    .append(nullToDash(r.get("name"))).append("｜")
                    .append(nullToDash(r.get("title"))).append("\n");
        }
        sb.append("可说：确认计划#ID出院");
        return sb.toString();
    }

    private String listOpenAlerts() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                        SELECT a.id, a.patient_id, a.level, a.reason, p.name
                        FROM alert_event a LEFT JOIN patient p ON p.id=a.patient_id
                        WHERE a.status='OPEN' ORDER BY FIELD(a.level,'RED','YELLOW','GREEN'), a.id DESC LIMIT 20
                        """);
        if (rows.isEmpty()) {
            return "当前没有未处理告警。";
        }
        StringBuilder sb = new StringBuilder("【未处理告警】").append(rows.size()).append(" 条\n");
        for (Map<String, Object> r : rows) {
            sb.append("告警#").append(r.get("id")).append(" ")
                    .append(r.get("level")).append(" 患者#").append(r.get("patient_id"))
                    .append(" ").append(nullToDash(r.get("name")))
                    .append("｜").append(nullToDash(r.get("reason"))).append("\n");
        }
        return sb.toString();
    }

    private String listPendingTasks(JsonNode args) {
        Long pid = resolvePatientId(args);
        if (pid == null) {
            return "请指定患者以查看待办，例如：查看患者#12的待办";
        }
        var tasks = carePlanService.listPendingTasks(pid);
        if (tasks == null || tasks.isEmpty()) {
            return "患者#" + pid + " 当前无待办随访任务。";
        }
        StringBuilder sb = new StringBuilder("患者#").append(pid).append(" 待办 ").append(tasks.size()).append("：\n");
        for (Map<String, Object> t : tasks) {
            sb.append("- 任务#").append(t.get("id")).append(" ")
                    .append(nullToDash(t.get("task_kind"))).append(" ")
                    .append(nullToDash(t.get("status"))).append("\n");
        }
        return sb.toString();
    }

    private String showTrend(JsonNode args) {
        Long pid = resolvePatientId(args);
        if (pid == null) {
            return "请指定患者以查看趋势，例如：马卫国近7日趋势";
        }
        int days = args.path("days").asInt(7);
        if (days <= 0 || days > 60) {
            days = 7;
        }
        return patientTrendService.renderTrendMarkdown(pid, days);
    }

    private String deviceSnapshot(JsonNode args) {
        Long pid = resolvePatientId(args);
        if (pid == null) {
            return "请指定患者以查看手环数据。";
        }
        Map<String, Object> snap = args.path("refresh").asBoolean(false)
                ? deviceMockService.snapshotAndSave(pid)
                : deviceMockService.latest(pid);
        return "患者#" + pid + " 手环数据：\n" + snap;
    }

    private String queryRecovery(JsonNode args, String chatId, String originalText) {
        String name = args.path("patientName").asText("");
        String q = name.isBlank() ? originalText : ("查" + name + "恢复");
        if (doctorQueryService.tryHandle(q, chatId)) {
            return "__ALREADY_SENT__";
        }
        Long pid = resolvePatientId(args);
        if (pid != null && doctorQueryService.tryHandle("查患者#" + pid + "恢复", chatId)) {
            return "__ALREADY_SENT__";
        }
        return "未能查询恢复情况，请说明患者姓名。";
    }

    private String createPatient(JsonNode args) {
        String name = args.path("name").asText("");
        String diagnosis = args.path("diagnosis").asText("骨科术后");
        if (name.isBlank()) {
            return "新建患者需要姓名，例如：新建患者张三，诊断膝关节置换术后";
        }
        Map<String, String> req = new HashMap<>();
        req.put("name", name);
        req.put("diagnosis", diagnosis);
        if (args.hasNonNull("doctorName")) {
            req.put("doctorName", args.path("doctorName").asText());
        }
        Map<String, Object> created = patientService.create(req);
        return "已创建患者#" + created.get("id") + " " + created.get("name")
                + "（" + created.get("diagnosis") + "）";
    }

    private String updatePatient(JsonNode args) {
        Long pid = resolvePatientId(args);
        if (pid == null) {
            pid = resolvePatientIdIncludingArchived(args);
        }
        if (pid == null) {
            return "请指定要修改的患者。";
        }
        Map<String, String> req = new HashMap<>();
        putIfPresent(req, args, "name");
        putIfPresent(req, args, "diagnosis");
        putIfPresent(req, args, "doctorName");
        putIfPresent(req, args, "dischargeAt");
        putIfPresent(req, args, "caseNotes");
        if (req.isEmpty()) {
            return "请说明要改的字段，例如：把患者#12诊断改成右膝置换术后";
        }
        Map<String, Object> updated = patientService.update(pid, req);
        return "已更新患者#" + pid + " " + updated.get("name")
                + "｜诊断 " + updated.get("diagnosis")
                + "｜医生 " + updated.get("doctorName");
    }

    private String restorePatient(JsonNode args) {
        Long pid = resolvePatientIdIncludingArchived(args);
        if (pid == null) {
            return "请指定要恢复的患者，例如：恢复患者#12";
        }
        int n = jdbcTemplate.update(
                "UPDATE patient SET status='ACTIVE', updated_at=NOW() WHERE id=? AND status='ARCHIVED'",
                pid);
        if (n == 0) {
            String st = jdbcTemplate.query(
                    "SELECT status FROM patient WHERE id=?",
                    rs -> rs.next() ? rs.getString(1) : null, pid);
            return st == null ? "患者不存在。" : ("患者#" + pid + " 当前状态为 " + st + "，无需恢复或无法恢复。");
        }
        return "已恢复患者#" + pid + " 为 ACTIVE（随访计划需重新生成/确认）。";
    }

    private String intakeCase(JsonNode args, String chatId) {
        String text = args.path("text").asText("").trim();
        if (text.isBlank()) {
            return "请提供病历原文，例如：根据病历建档：姓名张三，诊断…";
        }
        String rid = blank(demoReceiveId) ? chatId : demoReceiveId;
        var r = caseIntakeService.intakeFromText(text, demoReceiveIdType, rid);
        return "已识别并推送建档确认卡：" + r.get("tip") + " draftKey=" + r.get("draftKey");
    }

    private String createDraftPlan(JsonNode args, String chatId) {
        Long pid = resolvePatientId(args);
        if (pid == null) {
            return "请指定患者以生成计划草稿。";
        }
        String rid = blank(demoReceiveId) ? chatId : demoReceiveId;
        var r = carePlanService.createDraftAndNotifyDoctor(pid, demoReceiveIdType, rid);
        return "已为患者#" + pid + " 生成计划草稿并推送确认卡：" + r;
    }

    private String editPlan(JsonNode args, String chatId) {
        long planId = resolvePlanId(args);
        String editText = args.path("text").asText("");
        if (planId <= 0 || editText.isBlank()) {
            return "改计划需要 planId 与内容，例如：把计划#22随访改成7天";
        }
        var r = carePlanEditService.applyDoctorText(planId, editText, chatId);
        return String.valueOf(r.get("message"));
    }

    private String pushFollowup(JsonNode args) {
        Long pid = resolvePatientId(args);
        if (pid == null) {
            return "请指定患者以推送随访。";
        }
        return "推送结果：" + carePlanService.pushNextQuestion(pid);
    }

    private String generateBriefing(JsonNode args, String chatId) {
        Long pid = resolvePatientId(args);
        if (pid == null) {
            return "请指定患者以生成诊前简报。";
        }
        String rid = blank(demoReceiveId) ? chatId : demoReceiveId;
        return "简报结果：" + briefingService.generateAndSend(pid, demoReceiveIdType, rid);
    }

    private String issueCode(JsonNode args, String chatId) {
        Long pid = resolvePatientId(args);
        if (pid == null) {
            return "请指定患者以发放病患码。";
        }
        String rid = blank(demoReceiveId) ? chatId : demoReceiveId;
        return "病患码：" + dischargeQrService.issueAndPushToFeishu(pid, demoReceiveIdType, rid);
    }

    private String notifyPatient(JsonNode args) {
        Long pid = resolvePatientId(args);
        String message = args.path("message").asText("");
        if (pid == null || message.isBlank()) {
            return "通知患者需要 patientId 与 message。";
        }
        boolean ok = patientNotifyService.notifyPatient(pid, "[医生] " + message);
        return ok ? "已通知患者#" + pid : "已记录给患者#" + pid + "（通道可能未绑定）";
    }

    private String replyPatient(JsonNode args) {
        Long pid = resolvePatientId(args);
        String message = args.path("message").asText("").trim();
        if (pid == null || message.isBlank()) {
            return "回复患者需要指定患者与内容，例如：回复患者#12：伤口保持干燥";
        }
        boolean ok = patientNotifyService.notifyPatient(pid, "[医生回复] " + message);
        jdbcTemplate.update(
                "INSERT INTO encounter_log(patient_id, direction, content) VALUES (?, 'OUT', ?)",
                pid, "[医生回复] " + message);
        long alertId = args.path("alertId").asLong(0);
        if (alertId > 0) {
            jdbcTemplate.update(
                    """
                            UPDATE alert_event
                            SET status='ACKED', doctor_action='REPLY_PATIENT',
                                handled_at=NOW(), updated_at=NOW()
                            WHERE id=? AND patient_id=?
                            """,
                    alertId, pid);
        }
        return ok
                ? "已回复患者#" + pid + (alertId > 0 ? "，并关闭告警#" + alertId : "")
                : "已记录回复给患者#" + pid + "（通道可能未绑定）";
    }

    private String ignoreAlert(JsonNode args, String chatId) {
        long alertId = args.path("alertId").asLong(0);
        if (alertId <= 0) {
            return "请提供 alertId，可先说「查看未处理告警」。";
        }
        String note = args.path("note").asText("").trim();
        if (!note.isBlank()) {
            Long pid = jdbcTemplate.query(
                    "SELECT patient_id FROM alert_event WHERE id=?",
                    rs -> rs.next() ? rs.getLong(1) : null, alertId);
            if (pid == null) {
                return "告警不存在。";
            }
            return alertActionService.completeIgnoreNote(alertId, pid, note, "feishu-nl");
        }
        return alertAction("IGNORE_NOTE", args, chatId);
    }

    private String adaptSuggest(JsonNode args) {
        Long pid = resolvePatientId(args);
        if (pid == null) {
            return "请指定患者以评估计划自适应。";
        }
        Map<String, Object> r = carePlanAdaptationService.evaluateAndMaybeSuggest(pid);
        if (!Boolean.TRUE.equals(r.get("suggested"))) {
            return "患者#" + pid + " 暂无自适应建议（" + nullToDash(r.get("reason")) + "）。";
        }
        return "已生成自适应建议并推送飞书卡片：计划#" + r.get("planId")
                + "\n建议：" + r.get("suggestions")
                + "\n也可说：确认自适应 计划#" + r.get("planId") + " " + r.get("applyNl");
    }

    private String confirmAdapt(JsonNode args) {
        long planId = resolvePlanId(args);
        String applyNl = args.path("applyNl").asText("").trim();
        if (planId <= 0 || applyNl.isBlank()) {
            return "确认自适应需要 planId 与 applyNl。";
        }
        return carePlanAdaptationService.confirmAdapt(planId, applyNl, "feishu-nl");
    }

    private String alertAction(String code, JsonNode args, String chatId) {
        long alertId = args.path("alertId").asLong(0);
        if (alertId <= 0) {
            return "请提供 alertId，可先说「查看未处理告警」。";
        }
        String toast = alertActionService.handleDoctorAction(
                alertId, 0L, code, "feishu-nl", chatId, "", "");
        return "告警处置：" + toast;
    }

    private long resolvePlanId(JsonNode args) {
        if (args != null && args.has("planId") && args.get("planId").canConvertToLong()) {
            return args.get("planId").asLong(0);
        }
        return 0;
    }

    private Long resolvePatientId(JsonNode args) {
        return resolvePatientId(args, true);
    }

    private Long resolvePatientIdIncludingArchived(JsonNode args) {
        return resolvePatientId(args, false);
    }

    private Long resolvePatientId(JsonNode args, boolean activeOnly) {
        if (args != null && args.has("patientId") && args.get("patientId").canConvertToLong()) {
            long id = args.get("patientId").asLong();
            if (id > 0) {
                return id;
            }
        }
        if (args != null) {
            String name = args.path("patientName").asText("").trim();
            if (!name.isBlank()) {
                String sqlExact = activeOnly
                        ? "SELECT id FROM patient WHERE status='ACTIVE' AND name=? ORDER BY id DESC LIMIT 1"
                        : "SELECT id FROM patient WHERE name=? ORDER BY id DESC LIMIT 1";
                String sqlLike = activeOnly
                        ? "SELECT id FROM patient WHERE status='ACTIVE' AND name LIKE ? ORDER BY id DESC LIMIT 1"
                        : "SELECT id FROM patient WHERE name LIKE ? ORDER BY id DESC LIMIT 1";
                List<Map<String, Object>> rows = jdbcTemplate.queryForList(sqlExact, name);
                if (rows.isEmpty()) {
                    rows = jdbcTemplate.queryForList(sqlLike, "%" + name + "%");
                }
                if (!rows.isEmpty()) {
                    return ((Number) rows.get(0).get("id")).longValue();
                }
            }
        }
        return null;
    }

    private static void putIfPresent(Map<String, String> req, JsonNode args, String field) {
        if (args != null && args.hasNonNull(field) && !args.path(field).asText("").isBlank()) {
            req.put(field, args.path(field).asText().trim());
        }
    }

    private int countActivePlans(Long patientId) {
        Integer n = patientId == null
                ? jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM care_plan WHERE status IN ('ACTIVE','DRAFT')", Integer.class)
                : jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM care_plan WHERE patient_id=? AND status IN ('ACTIVE','DRAFT')",
                Integer.class, patientId);
        return n == null ? 0 : n;
    }

    private int countActivePatients() {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM patient WHERE status='ACTIVE'", Integer.class);
        return n == null ? 0 : n;
    }

    private static String stripMentions(String text) {
        return text == null ? null : text.replaceAll("@_user_\\d+", "").replaceAll("@\\S+", "").trim();
    }

    private static String extractJson(String raw) {
        String s = raw.trim();
        if (s.startsWith("```")) {
            s = s.replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "");
        }
        int a = s.indexOf('{');
        int b = s.lastIndexOf('}');
        return (a >= 0 && b > a) ? s.substring(a, b + 1) : s;
    }

    private static String join(String say, String result) {
        String a = say == null ? "" : say.trim();
        String b = result == null ? "" : result.trim();
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;
        if (UNSUPPORTED_MSG.equals(b) && !a.isEmpty()) {
            return b;
        }
        return a + "\n\n" + b;
    }

    private static String nullToDash(Object o) {
        return o == null || String.valueOf(o).isBlank() ? "—" : String.valueOf(o);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String helpText() {
        return """
                已配置工具（自然语言即可）：
                · 查看：患者/搜索、看板、计划、草稿计划、告警、待办、恢复、趋势、手环、知识库
                · 档案：新建/修改/恢复患者、病历建档
                · 随访：草稿计划、改计划、推随访、批量推到期、简报、病患码、通知/回复患者、自适应建议
                · 告警：已阅 / 电话随访 / 提前复诊 / 忽略并注明
                · 高危确认：取消计划、确认出院、删除患者
                
                若能力不在上列，会回复：暂时未配置相关工具
                """.trim();
    }
}
