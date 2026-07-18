package com.careloop.session;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 飞书群内临时会话：改计划 / 回复指定患者。按 chatId 隔离，带过期。
 */
@Component
public class FeishuDoctorSessionStore {

    public enum Mode {
        EDIT_PLAN,
        REPLY_PATIENT,
        IGNORE_NOTE,
        BRIEF_IGNORE_NOTE
    }

    public record Session(Mode mode, long planId, long patientId, long alertId,
                          String chatId, Instant expireAt) {
        public boolean expired() {
            return Instant.now().isAfter(expireAt);
        }
    }

    private final ConcurrentHashMap<String, Session> byChat = new ConcurrentHashMap<>();

    public void startEditPlan(String chatId, long planId, long patientId) {
        byChat.put(chatId, new Session(Mode.EDIT_PLAN, planId, patientId, 0L, chatId,
                Instant.now().plusSeconds(1800)));
    }

    public void startReplyPatient(String chatId, long patientId, long alertId) {
        byChat.put(chatId, new Session(Mode.REPLY_PATIENT, 0L, patientId, alertId, chatId,
                Instant.now().plusSeconds(1800)));
    }

    public void startIgnoreNote(String chatId, long patientId, long alertId) {
        byChat.put(chatId, new Session(Mode.IGNORE_NOTE, 0L, patientId, alertId, chatId,
                Instant.now().plusSeconds(1800)));
    }

    public void startBriefIgnoreNote(String chatId, long patientId, long briefingId) {
        byChat.put(chatId, new Session(Mode.BRIEF_IGNORE_NOTE, 0L, patientId, briefingId, chatId,
                Instant.now().plusSeconds(1800)));
    }

    public Session get(String chatId) {
        if (chatId == null) {
            return null;
        }
        Session s = byChat.get(chatId);
        if (s == null) {
            return null;
        }
        if (s.expired()) {
            byChat.remove(chatId);
            return null;
        }
        return s;
    }

    public void clear(String chatId) {
        if (chatId != null) {
            byChat.remove(chatId);
        }
    }

    public Map<String, Session> snapshot() {
        return Map.copyOf(byChat);
    }
}
