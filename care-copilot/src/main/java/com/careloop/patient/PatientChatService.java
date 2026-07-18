package com.careloop.patient;

import com.careloop.agent.PatientAgentService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * 患者消息入口：委托智能体；强制要求调用方传入正确的 patientId（由随访页 token 绑定解析）。
 */
@Service
public class PatientChatService {

    private final PatientAgentService patientAgentService;

    public PatientChatService(PatientAgentService patientAgentService) {
        this.patientAgentService = patientAgentService;
    }

    @Transactional
    public Map<String, Object> handlePatientMessage(long patientId, String text, Long taskId,
                                                    String receiveIdType, String receiveId) {
        // receiveId 保留参数兼容；飞书告警目标由智能体使用配置的医护群，避免串患者会话
        return patientAgentService.handle(patientId, text, taskId);
    }
}
