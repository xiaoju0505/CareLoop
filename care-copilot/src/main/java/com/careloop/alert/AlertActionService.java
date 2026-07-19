package com.careloop.alert;

import com.careloop.bind.DischargeQrService;
import com.careloop.care.CarePlanAdaptationService;
import com.careloop.care.CarePlanEditService;
import com.careloop.care.CarePlanService;
import com.careloop.caseintake.FeishuCaseIntakeService;
import com.careloop.feishu.FeishuMessageService;
import com.careloop.ortho.OrthoKnowledgeService;
import com.careloop.patient.PatientNotifyService;
import com.careloop.session.FeishuDoctorSessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class AlertActionService {

    private static final Logger log = LoggerFactory.getLogger(AlertActionService.class);

    private final JdbcTemplate jdbcTemplate;
    private final CarePlanService carePlanService;
    private final CarePlanEditService carePlanEditService;
    private final DischargeQrService dischargeQrService;
    private final PatientNotifyService patientNotifyService;
    private final OrthoKnowledgeService knowledgeService;
    private final FeishuCaseIntakeService caseIntakeService;
    private final FeishuDoctorSessionStore sessionStore;
    private final FeishuMessageService feishuMessageService;
    private final CarePlanAdaptationService carePlanAdaptationService;

    public AlertActionService(JdbcTemplate jdbcTemplate,
                              CarePlanService carePlanService,
                              CarePlanEditService carePlanEditService,
                              DischargeQrService dischargeQrService,
                              PatientNotifyService patientNotifyService,
                              OrthoKnowledgeService knowledgeService,
                              FeishuCaseIntakeService caseIntakeService,
                              FeishuDoctorSessionStore sessionStore,
                              FeishuMessageService feishuMessageService,
                              CarePlanAdaptationService carePlanAdaptationService) {
        this.jdbcTemplate = jdbcTemplate;
        this.carePlanService = carePlanService;
        this.carePlanEditService = carePlanEditService;
        this.dischargeQrService = dischargeQrService;
        this.patientNotifyService = patientNotifyService;
        this.knowledgeService = knowledgeService;
        this.caseIntakeService = caseIntakeService;
        this.sessionStore = sessionStore;
        this.feishuMessageService = feishuMessageService;
        this.carePlanAdaptationService = carePlanAdaptationService;
    }

    @Transactional
    public String handleDoctorAction(long alertId, String actionCode, String operatorOpenId) {
        return handleDoctorAction(alertId, 0L, actionCode, operatorOpenId, null, null, null);
    }

    @Transactional
    public String handleDoctorAction(long alertId, long planId, String actionCode, String operatorOpenId) {
        return handleDoctorAction(alertId, planId, actionCode, operatorOpenId, null, null, null);
    }

    @Transactional
    public String handleDoctorAction(long alertId, long planId, String actionCode,
                                     String operatorOpenId, String openChatId) {
        return handleDoctorAction(alertId, planId, actionCode, operatorOpenId, openChatId, null, null);
    }

    @Transactional
    public String handleDoctorAction(long alertId, long planId, String actionCode,
                                     String operatorOpenId, String openChatId, String draftKey) {
        return handleDoctorAction(alertId, planId, actionCode, operatorOpenId, openChatId, draftKey, null);
    }

    @Transactional
    public String handleDoctorAction(long alertId, long planId, String actionCode,
                                     String operatorOpenId, String openChatId, String draftKey,
                                     String applyNl) {
        String action = actionCode == null ? "" : actionCode.trim().toUpperCase();
        log.info("医生操作 alertId={}, planId={}, action={}, operator={}, chat={}, draft={}",
                alertId, planId, action, operatorOpenId, openChatId, draftKey);

        if ("CONFIRM_CASE_AND_PLAN".equals(action)) {
            return caseIntakeService.confirmDraft(draftKey, true, openChatId);
        }
        if ("CONFIRM_CASE".equals(action)) {
            return caseIntakeService.confirmDraft(draftKey, false, openChatId);
        }
        if ("REJECT_CASE".equals(action)) {
            return caseIntakeService.rejectDraft(draftKey);
        }

        if ("CONFIRM_PLAN".equals(action)) {
            long id = planId > 0 ? planId : alertId;
            Map<String, Object> confirmed = carePlanService.confirmPlan(id, operatorOpenId);
            if (!Boolean.TRUE.equals(confirmed.get("ok"))) {
                return String.valueOf(confirmed.get("message"));
            }
            long patientId = ((Number) confirmed.get("patientId")).longValue();
            try {
                String receiveType = (openChatId != null && !openChatId.isBlank()) ? "chat_id" : null;
                String receiveId = (openChatId != null && !openChatId.isBlank()) ? openChatId : null;
                Map<String, Object> qr = dischargeQrService.issueAndPushToFeishu(patientId, receiveType, receiveId);
                boolean imageOk = Boolean.TRUE.equals(qr.get("imageOk"));
                return imageOk
                        ? "已确认出院，8 位专属病患码已发到本会话，请告知患者打开网页输入登录"
                        : "已确认出院，二维码链接已发到本会话（图片直传需开通飞书上传权限）。请点开「打开二维码」出示给患者";
            } catch (Exception e) {
                log.error("推送出院二维码失败", e);
                return "计划已确认，但二维码推送失败：" + e.getMessage();
            }
        }
        if ("PLAN_LATER".equals(action)) {
            return "已记录：稍后处理计划";
        }
        if ("SET_FOLLOWUP_DAYS".equals(action)) {
            long id = planId > 0 ? planId : alertId;
            String days = applyNl == null ? "" : applyNl.replaceAll("[^0-9]", "");
            if (days.isBlank()) {
                return "未选择随访天数";
            }
            var result = carePlanEditService.applyDoctorText(id, "随访天数：" + days, openChatId);
            return Boolean.TRUE.equals(result.get("ok"))
                    ? "已更新随访天数为 " + days + " 天，卡片已刷新"
                    : String.valueOf(result.get("message"));
        }
        if ("SET_DAILY_TIME".equals(action)) {
            long id = planId > 0 ? planId : alertId;
            String time = applyNl == null ? "" : applyNl.trim();
            if (time.isBlank()) {
                return "未选择日常记录时间";
            }
            var result = carePlanEditService.applyDoctorText(id, "每日时间：" + time, openChatId);
            return Boolean.TRUE.equals(result.get("ok"))
                    ? "已更新日常健康记录时间为 " + time + "，卡片已刷新"
                    : String.valueOf(result.get("message"));
        }
        if ("CONFIRM_ADAPT".equals(action)) {
            long id = planId > 0 ? planId : alertId;
            String nl = applyNl;
            if (nl == null || nl.isBlank()) {
                nl = "每日时间：10:00；加要求：请重点关注伤口渗液与疼痛是否加重";
            }
            return carePlanAdaptationService.confirmAdapt(id, nl, operatorOpenId);
        }

        if ("EDIT_PLAN".equals(action)) {
            long id = resolvePlanId(planId, alertId);
            if (id <= 0) {
                return "未找到可编辑的随访计划";
            }
            Map<String, Object> plan = carePlanService.getPlan(id);
            long patientId = ((Number) plan.get("patientId")).longValue();
            String patientName = jdbcTemplate.query(
                    "SELECT name FROM patient WHERE id = ?",
                    rs -> rs.next() ? rs.getString(1) : "患者",
                    patientId
            );
            String chat = (openChatId != null && !openChatId.isBlank()) ? openChatId : null;
            if (chat != null) {
                sessionStore.startEditPlan(chat, id, patientId);
                feishuMessageService.sendText("chat_id", chat,
                        carePlanEditService.editGuide(id, patientName));
            }
            return "已进入飞书修改模式（患者#" + patientId + "）。请直接在群里发送修改内容";
        }

        if ("ACK_READ".equals(action) || "BRIEF_ACK".equals(action)) {
            return ackWriteback(alertId, action, operatorOpenId, "已阅",
                    "医生已阅本条告警/报告，继续按计划随访。");
        }
        if ("CALL_FOLLOWUP".equals(action) || "BRIEF_CALL".equals(action)) {
            Long patientId = resolvePatientId(alertId, planId);
            writeDoctorAction(alertId, "CALL_FOLLOWUP", operatorOpenId, null);
            if (patientId != null) {
                jdbcTemplate.update(
                        "INSERT INTO encounter_log(patient_id, direction, content) VALUES (?, 'OUT', ?)",
                        patientId, "[医生决策] 电话随访 · operator=" + operatorOpenId
                );
                patientNotifyService.notifyPatient(patientId,
                        "医生将尽快与您电话随访，请保持手机畅通；也可继续在随访页补充不适描述。");
            }
            return "已记录：电话随访" + (patientId == null ? "" : "（患者#" + patientId + "）");
        }
        if ("BRIEF_EARLY".equals(action) || "EARLY_RECHECK".equals(action)) {
            Long patientId = resolvePatientId(alertId, planId);
            writeDoctorAction(alertId, "EARLY_RECHECK", operatorOpenId, null);
            if (patientId != null) {
                String msg = knowledgeService.earlyRecheckMessage();
                patientNotifyService.notifyPatient(patientId, msg);
                jdbcTemplate.update(
                        "INSERT INTO encounter_log(patient_id, direction, content) VALUES (?, 'OUT', ?)",
                        patientId, "[医生决策] 建议提前加号/复诊 · operator=" + operatorOpenId
                );
                return "已建议患者提前复诊，并已通知患者";
            }
            return "已记录：建议提前加号";
        }
        if ("IGNORE_NOTE".equals(action) || "BRIEF_IGNORE_NOTE".equals(action)) {
            Long patientId = resolvePatientId(alertId, planId);
            String chat = (openChatId != null && !openChatId.isBlank()) ? openChatId : null;
            if (chat != null && patientId != null) {
                if ("BRIEF_IGNORE_NOTE".equals(action)) {
                    sessionStore.startBriefIgnoreNote(chat, patientId, alertId);
                } else {
                    sessionStore.startIgnoreNote(chat, patientId, alertId);
                }
                feishuMessageService.sendText("chat_id", chat,
                        "【忽略并注明原因】请直接回复忽略原因（患者#" + patientId
                                + "，将写入档案，30分钟内有效）。");
            }
            return "请在群里注明忽略原因，将回写档案";
        }
        if ("REPLY_CONSULT".equals(action)) {
            if (alertId <= 0) {
                return "缺少告警ID，无法定位患者";
            }
            Long patientId = jdbcTemplate.query(
                    "SELECT patient_id FROM alert_event WHERE id = ?",
                    rs -> rs.next() ? rs.getLong(1) : null,
                    alertId
            );
            if (patientId == null) {
                return "告警不存在";
            }
            String chat = (openChatId != null && !openChatId.isBlank()) ? openChatId : null;
            if (chat != null) {
                sessionStore.startReplyPatient(chat, patientId, alertId);
                feishuMessageService.sendText("chat_id", chat,
                        "【回复患者#" + patientId + "】请直接在本群输入要发给该患者的内容（30分钟内有效）。\n"
                                + "只会发给此患者，不会串到其他病人。");
            }
            return "请在群里输入回复内容，将仅发给患者#" + patientId;
        }
        if ("ISSUE_QR".equals(action)) {
            long patientId = planId > 0 ? planId : alertId;
            if (patientId <= 0) {
                return "缺少患者信息，无法生成二维码";
            }
            try {
                String receiveType = (openChatId != null && !openChatId.isBlank()) ? "chat_id" : null;
                String receiveId = (openChatId != null && !openChatId.isBlank()) ? openChatId : null;
                Long realPatientId = jdbcTemplate.query(
                        "SELECT patient_id FROM care_plan WHERE id = ?",
                        rs -> rs.next() ? rs.getLong(1) : null,
                        patientId
                );
                long pid = realPatientId != null ? realPatientId : patientId;
                dischargeQrService.issueAndPushToFeishu(pid, receiveType, receiveId);
                return "专属随访二维码已发送到本会话";
            } catch (Exception e) {
                log.error("生成二维码失败", e);
                return "生成二维码失败：" + e.getMessage();
            }
        }

        String label = switch (action) {
            case "OBSERVE" -> "继续观察";
            case "EARLY_RECHECK" -> "提前复诊";
            case "NOTIFY_PATIENT" -> "通知患者";
            case "MANUAL" -> "医生自行处理";
            default -> "未知操作(" + action + ")";
        };

        if (alertId > 0) {
            int updated = jdbcTemplate.update(
                    """
                            UPDATE alert_event
                            SET status = 'ACKED',
                                doctor_action = ?,
                                handled_by = ?,
                                handled_at = NOW(),
                                updated_at = NOW()
                            WHERE id = ?
                            """,
                    action, operatorOpenId, alertId
            );
            if (updated == 0) {
                return "已记录：" + label + "（告警不存在或已处理）";
            }

            Long patientId = jdbcTemplate.query(
                    "SELECT patient_id FROM alert_event WHERE id = ?",
                    rs -> rs.next() ? rs.getLong(1) : null,
                    alertId
            );
            if (patientId != null) {
                if ("NOTIFY_PATIENT".equals(action)) {
                    String msg = knowledgeService.notifyAfterDoctor();
                    boolean sent = patientNotifyService.notifyPatient(patientId, msg);
                    return sent ? "已飞书提醒并附随访页链接" : "已记录通知内容（请转发随访页）";
                }
                if ("EARLY_RECHECK".equals(action)) {
                    String msg = knowledgeService.earlyRecheckMessage();
                    patientNotifyService.notifyPatient(patientId, msg);
                    jdbcTemplate.update(
                            "INSERT INTO encounter_log(patient_id, direction, content) VALUES (?, 'OUT', ?)",
                            patientId, "[医生决策] 提前复诊"
                    );
                    return "已建议患者提前复诊，并已通知患者";
                }
                if ("OBSERVE".equals(action)) {
                    String msg = "医生已查看您的情况，建议继续按计划观察与康复，有不适及时回复。";
                    patientNotifyService.notifyPatient(patientId, msg);
                }
            }
        }

        return "已处理：" + label;
    }

    private String ackWriteback(long alertId, String action, String operatorOpenId,
                                String label, String patientMsg) {
        Long patientId = resolvePatientId(alertId, 0L);
        writeDoctorAction(alertId, action, operatorOpenId, null);
        if (patientId != null) {
            jdbcTemplate.update(
                    "INSERT INTO encounter_log(patient_id, direction, content) VALUES (?, 'OUT', ?)",
                    patientId, "[医生决策] " + label + " · operator=" + operatorOpenId
            );
            if (patientMsg != null && !patientMsg.isBlank() && alertId > 0
                    && !action.startsWith("BRIEF_")) {
                patientNotifyService.notifyPatient(patientId, patientMsg);
            }
        }
        return "已处理：" + label;
    }

    private void writeDoctorAction(long alertId, String action, String operatorOpenId, String note) {
        if (alertId <= 0) {
            return;
        }
        if (note != null && !note.isBlank()) {
            try {
                jdbcTemplate.update(
                        """
                                UPDATE alert_event
                                SET status = 'ACKED', doctor_action = ?, handled_by = ?,
                                    handled_at = NOW(), updated_at = NOW(), doctor_note = ?
                                WHERE id = ?
                                """,
                        action, operatorOpenId, note, alertId
                );
                return;
            } catch (Exception ignored) {
                // doctor_note 列可能尚未补齐
            }
        }
        jdbcTemplate.update(
                """
                        UPDATE alert_event
                        SET status = 'ACKED', doctor_action = ?, handled_by = ?,
                            handled_at = NOW(), updated_at = NOW()
                        WHERE id = ?
                        """,
                action, operatorOpenId, alertId
        );
    }

    private long resolvePlanId(long planId, long alertId) {
        if (planId > 0) {
            return planId;
        }
        if (alertId <= 0) {
            return 0L;
        }
        Long fromAlert = jdbcTemplate.query(
                """
                        SELECT cp.id FROM alert_event a
                        JOIN care_plan cp ON cp.patient_id = a.patient_id AND cp.status = 'ACTIVE'
                        WHERE a.id = ?
                        ORDER BY cp.id DESC LIMIT 1
                        """,
                rs -> rs.next() ? rs.getLong(1) : null,
                alertId
        );
        return fromAlert == null ? 0L : fromAlert;
    }

    private Long resolvePatientId(long alertId, long planId) {
        if (alertId > 0) {
            Long pid = jdbcTemplate.query(
                    "SELECT patient_id FROM alert_event WHERE id = ?",
                    rs -> rs.next() ? rs.getLong(1) : null,
                    alertId
            );
            if (pid != null) {
                return pid;
            }
            // 报告卡片可能把 briefingId 放在 alertId
            pid = jdbcTemplate.query(
                    "SELECT patient_id FROM previsit_briefing WHERE id = ?",
                    rs -> rs.next() ? rs.getLong(1) : null,
                    alertId
            );
            if (pid != null) {
                return pid;
            }
        }
        if (planId > 0) {
            return jdbcTemplate.query(
                    "SELECT patient_id FROM care_plan WHERE id = ?",
                    rs -> rs.next() ? rs.getLong(1) : null,
                    planId
            );
        }
        return null;
    }

    /** 供会话：写入忽略原因 */
    public String completeIgnoreNote(long alertId, long patientId, String note, String operatorHint) {
        writeDoctorAction(alertId, "IGNORE_NOTE", operatorHint == null ? "feishu" : operatorHint, note);
        jdbcTemplate.update(
                "INSERT INTO encounter_log(patient_id, direction, content) VALUES (?, 'OUT', ?)",
                patientId, "[医生决策] 忽略告警并注明：" + note
        );
        return "已回写忽略原因到患者#" + patientId + " 档案";
    }
}
