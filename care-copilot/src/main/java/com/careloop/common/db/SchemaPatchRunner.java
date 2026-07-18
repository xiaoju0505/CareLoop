package com.careloop.common.db;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动时补齐可选列，避免手工执行 patch。
 */
@Component
public class SchemaPatchRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaPatchRunner.class);

    private final JdbcTemplate jdbcTemplate;

    public SchemaPatchRunner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void patch() {
        addColumnIfMissing("patient", "case_notes",
                "ALTER TABLE patient ADD COLUMN case_notes TEXT NULL COMMENT '病例摘要/医嘱备注' AFTER diagnosis");
        addColumnIfMissing("previsit_briefing", "auto_generated",
                "ALTER TABLE previsit_briefing ADD COLUMN auto_generated TINYINT NOT NULL DEFAULT 0 AFTER risk_level");
        addColumnIfMissing("followup_task", "task_kind",
                "ALTER TABLE followup_task ADD COLUMN task_kind VARCHAR(16) NOT NULL DEFAULT 'FOLLOWUP' COMMENT 'FOLLOWUP统一术后随访(兼容旧NODE/DAILY)' AFTER day_offset");
        addColumnIfMissing("alert_event", "doctor_note",
                "ALTER TABLE alert_event ADD COLUMN doctor_note VARCHAR(512) NULL COMMENT '忽略/处置备注' AFTER doctor_action");
        addColumnIfMissing("followup_task", "question_form_json",
                "ALTER TABLE followup_task ADD COLUMN question_form_json JSON NULL COMMENT '选择题+自定义描述表单' AFTER question_text");
    }

    private void addColumnIfMissing(String table, String column, String ddl) {
        try {
            Integer cnt = jdbcTemplate.queryForObject(
                    """
                            SELECT COUNT(*) FROM information_schema.COLUMNS
                            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?
                            """,
                    Integer.class, table, column
            );
            if (cnt != null && cnt == 0) {
                jdbcTemplate.execute(ddl);
                log.info("已补齐表 {}.{}", table, column);
            }
        } catch (Exception e) {
            log.warn("补齐 {}.{} 失败（可忽略若已存在）: {}", table, column, e.getMessage());
        }
    }
}
