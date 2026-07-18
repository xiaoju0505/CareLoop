-- CareLoop MVP 初始库表
-- 在 MySQL 中执行：先创建库，再执行本脚本

CREATE DATABASE IF NOT EXISTS care_copilot
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE care_copilot;

-- 患者
CREATE TABLE IF NOT EXISTS patient (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  name          VARCHAR(64)  NOT NULL,
  gender        VARCHAR(16)  NULL,
  phone_mask    VARCHAR(32)  NULL COMMENT '脱敏手机号，如 138****1234',
  diagnosis     VARCHAR(256) NULL COMMENT '主诊断',
  case_notes    TEXT         NULL COMMENT '病例摘要/医嘱备注',
  doctor_name   VARCHAR(64)  NULL,
  discharge_at  DATETIME     NULL COMMENT '出院/离院时间',
  status        VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 微信/企微绑定
CREATE TABLE IF NOT EXISTS patient_binding (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  patient_id      BIGINT       NOT NULL,
  channel         VARCHAR(32)  NOT NULL DEFAULT 'WECOM' COMMENT 'WECOM / WX_OA / MOCK',
  channel_user_id VARCHAR(128) NOT NULL COMMENT 'openid 或 external_userid',
  bind_token      VARCHAR(64)  NULL COMMENT '扫码绑定用短期 token',
  bound_at        DATETIME     NULL,
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_channel_user (channel, channel_user_id),
  KEY idx_patient (patient_id),
  CONSTRAINT fk_binding_patient FOREIGN KEY (patient_id) REFERENCES patient(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 离院/随访计划
CREATE TABLE IF NOT EXISTS care_plan (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  patient_id   BIGINT       NOT NULL,
  title        VARCHAR(128) NOT NULL,
  content_json JSON         NULL COMMENT '计划节点、问题模板等',
  status       VARCHAR(32)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/ACTIVE/CLOSED',
  confirmed_by VARCHAR(64)  NULL COMMENT '飞书确认人',
  confirmed_at DATETIME     NULL,
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_patient (patient_id),
  CONSTRAINT fk_plan_patient FOREIGN KEY (patient_id) REFERENCES patient(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 随访任务（到点问病人）
CREATE TABLE IF NOT EXISTS followup_task (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  plan_id       BIGINT       NOT NULL,
  patient_id    BIGINT       NOT NULL,
  day_offset    INT          NOT NULL COMMENT '出院后第几天',
  question_text VARCHAR(512) NOT NULL,
  scheduled_at  DATETIME     NOT NULL,
  sent_at       DATETIME     NULL,
  answered_at   DATETIME     NULL,
  answer_text   TEXT         NULL,
  status        VARCHAR(32)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SENT/ANSWERED/TIMEOUT',
  created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_schedule (status, scheduled_at),
  KEY idx_patient (patient_id),
  CONSTRAINT fk_task_plan FOREIGN KEY (plan_id) REFERENCES care_plan(id),
  CONSTRAINT fk_task_patient FOREIGN KEY (patient_id) REFERENCES patient(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 对话/采集日志
CREATE TABLE IF NOT EXISTS encounter_log (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  patient_id   BIGINT       NOT NULL,
  direction    VARCHAR(16)  NOT NULL COMMENT 'IN/OUT',
  content      TEXT         NOT NULL,
  structured_json JSON      NULL COMMENT '抽取后的结构化字段',
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_patient_time (patient_id, created_at),
  CONSTRAINT fk_log_patient FOREIGN KEY (patient_id) REFERENCES patient(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 异常告警
CREATE TABLE IF NOT EXISTS alert_event (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  patient_id      BIGINT       NOT NULL,
  level           VARCHAR(8)   NOT NULL COMMENT 'GREEN/YELLOW/RED',
  reason          VARCHAR(512) NOT NULL,
  source_log_id   BIGINT       NULL,
  status          VARCHAR(32)  NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/ACKED/CLOSED',
  feishu_msg_id   VARCHAR(128) NULL,
  handled_by      VARCHAR(64)  NULL,
  handled_at      DATETIME     NULL,
  doctor_action   VARCHAR(64)  NULL COMMENT 'OBSERVE/NOTIFY_PATIENT/MANUAL',
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_status_level (status, level),
  KEY idx_patient (patient_id),
  CONSTRAINT fk_alert_patient FOREIGN KEY (patient_id) REFERENCES patient(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 诊前简报
CREATE TABLE IF NOT EXISTS previsit_briefing (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  patient_id   BIGINT       NOT NULL,
  visit_date   DATE         NULL,
  content_md   MEDIUMTEXT   NOT NULL,
  risk_level   VARCHAR(8)   NULL,
  feishu_doc_token VARCHAR(128) NULL,
  created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_patient (patient_id),
  CONSTRAINT fk_brief_patient FOREIGN KEY (patient_id) REFERENCES patient(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Demo 种子患者（可改）
INSERT INTO patient (name, gender, phone_mask, diagnosis, doctor_name, discharge_at, status)
VALUES ('张女士', '女', '138****8001', '膝关节置换术后', '李医生', NOW(), 'ACTIVE');
