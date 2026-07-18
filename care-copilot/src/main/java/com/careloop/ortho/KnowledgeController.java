package com.careloop.ortho;

import com.careloop.feishu.FeishuKnowledgePublisher;
import com.careloop.kb.OrthoBitableKnowledgeSync;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/kb")
public class KnowledgeController {

    private final OrthoKnowledgeService knowledgeService;
    private final FeishuKnowledgePublisher publisher;
    private final OrthoBitableKnowledgeSync bitableSync;

    public KnowledgeController(OrthoKnowledgeService knowledgeService,
                               FeishuKnowledgePublisher publisher,
                               OrthoBitableKnowledgeSync bitableSync) {
        this.knowledgeService = knowledgeService;
        this.publisher = publisher;
        this.bitableSync = bitableSync;
    }

    @GetMapping("/ortho")
    public Map<String, Object> summary() {
        return knowledgeService.summary();
    }

    @GetMapping("/ortho/bitable-status")
    public Map<String, Object> bitableStatus() {
        return bitableSync.status();
    }

    @GetMapping(value = "/ortho/markdown", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public String markdown() {
        return knowledgeService.toFeishuMarkdown();
    }

    @PostMapping("/ortho/publish-feishu")
    public Map<String, Object> publish(@RequestBody Map<String, String> req) {
        String type = req.getOrDefault("receiveIdType", "chat_id");
        String id = req.getOrDefault("receiveId", "");
        if (id.isBlank()) {
            throw new IllegalArgumentException("receiveId 不能为空");
        }
        return publisher.publishToFeishu(type, id);
    }

    /** 从飞书多维表格拉取并热更新助手知识库 */
    @PostMapping("/ortho/sync-bitable")
    public Map<String, Object> syncBitable() {
        return bitableSync.syncFromBitable();
    }

    /** 把本地 JSON 种子写入多维表格（初始化用） */
    @PostMapping("/ortho/seed-bitable")
    public Map<String, Object> seedBitable() {
        return bitableSync.seedBitableFromLocal();
    }
}
