package com.careloop.caseintake;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class CaseDraftStore {

    public record Draft(String key, Map<String, String> fields, String chatId, String messageId,
                        Instant createdAt, String source) {
    }

    /** 群内最近一份病例附件，供「这是病例」类短消息关联解析。 */
    public record PendingFile(String chatId, String messageId, String fileKey, String fileName,
                              String extractedText, Instant createdAt) {
    }

    private final ConcurrentHashMap<String, Draft> drafts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PendingFile> pendingFiles = new ConcurrentHashMap<>();

    public Draft save(Map<String, String> fields, String chatId, String messageId, String source) {
        String key = "cd_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Draft d = new Draft(key, fields, chatId, messageId, Instant.now(), source);
        drafts.put(key, d);
        return d;
    }

    public Draft get(String key) {
        return drafts.get(key);
    }

    public void remove(String key) {
        drafts.remove(key);
    }

    public void putPendingFile(PendingFile file) {
        if (file == null || file.chatId() == null) {
            return;
        }
        pendingFiles.put(file.chatId(), file);
    }

    public PendingFile getPendingFile(String chatId) {
        if (chatId == null) {
            return null;
        }
        PendingFile f = pendingFiles.get(chatId);
        if (f == null) {
            return null;
        }
        // 30 分钟内有效
        if (f.createdAt().isBefore(Instant.now().minusSeconds(1800))) {
            pendingFiles.remove(chatId);
            return null;
        }
        return f;
    }

    public void clearPendingFile(String chatId) {
        if (chatId != null) {
            pendingFiles.remove(chatId);
        }
    }
}
