-- v2：病例备注 + 简报去重标记
USE care_copilot;

ALTER TABLE patient
  ADD COLUMN IF NOT EXISTS case_notes TEXT NULL COMMENT '病例摘要/医嘱备注' AFTER diagnosis;

ALTER TABLE previsit_briefing
  ADD COLUMN IF NOT EXISTS auto_generated TINYINT NOT NULL DEFAULT 0 AFTER risk_level;

-- MySQL 8.0.12 以下无 IF NOT EXISTS，若报错可手工执行：
-- ALTER TABLE patient ADD COLUMN case_notes TEXT NULL COMMENT '病例摘要/医嘱备注' AFTER diagnosis;
