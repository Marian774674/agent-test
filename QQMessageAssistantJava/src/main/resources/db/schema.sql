-- 由 design/database_schema.md 落地；幂等，可重复执行。
CREATE TABLE IF NOT EXISTS `draft` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `type` VARCHAR(16) NOT NULL COMMENT 'REPLY|FILE|MEMO|FACT',
  `sender_id` BIGINT UNSIGNED NOT NULL,
  `chat_id` BIGINT UNSIGNED NULL,
  `title` VARCHAR(255) NULL COMMENT '列表展示用简短标题',
  `payload` JSON NOT NULL COMMENT '各 type 自定结构',
  `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING|CONFIRMED|REJECTED',
  `confirmed_at` DATETIME NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `is_deleted` TINYINT(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_status_created` (`status`, `created_at`),
  KEY `idx_type_status` (`type`, `status`),
  KEY `idx_sender` (`sender_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='统一草稿中心';

CREATE TABLE IF NOT EXISTS `message_log` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `sender_id` BIGINT UNSIGNED NOT NULL,
  `sender_name` VARCHAR(64) NULL COMMENT '发送者昵称',
  `chat_id` BIGINT UNSIGNED NULL,
  `chat_name` VARCHAR(128) NULL COMMENT '群名；私聊为 NULL',
  `conversation_id` VARCHAR(64) NULL COMMENT 'Dify conversation_id',
  `message_id` VARCHAR(64) NULL COMMENT '平台消息 id',
  `dedupe_key` VARCHAR(128) NULL COMMENT '幂等键，重复写入被忽略',
  `direction` VARCHAR(8) NOT NULL COMMENT 'IN|OUT',
  `reply_kind` VARCHAR(8) NULL COMMENT 'OUT 专用：TASK|CHAT',
  `msg_type` VARCHAR(16) NOT NULL COMMENT 'text|image|file...',
  `content` TEXT NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dedupe` (`dedupe_key`),
  KEY `idx_sender_created` (`sender_id`, `created_at`),
  KEY `idx_created` (`created_at`),
  KEY `idx_conversation` (`conversation_id`),
  KEY `idx_chat_created` (`chat_id`, `created_at`),
  FULLTEXT KEY `ft_content` (`content`) WITH PARSER ngram
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='统一消息日志';

CREATE TABLE IF NOT EXISTS `memo` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `sender_id` BIGINT UNSIGNED NOT NULL,
  `chat_id` BIGINT UNSIGNED NULL,
  `title` VARCHAR(255) NOT NULL,
  `content` TEXT NULL,
  `importance` TINYINT UNSIGNED NOT NULL COMMENT '1-5',
  `category` VARCHAR(32) NULL,
  `due_time` DATETIME NULL,
  `status` VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  `source_msg_id` BIGINT UNSIGNED NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `is_deleted` TINYINT(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  KEY `idx_sender_status` (`sender_id`, `status`),
  KEY `idx_status_due` (`status`, `due_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='备忘录';

CREATE TABLE IF NOT EXISTS `memo_reminder` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `memo_id` BIGINT UNSIGNED NOT NULL,
  `offset_label` VARCHAR(16) NOT NULL,
  `remind_at` DATETIME NOT NULL,
  `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  `sent_at` DATETIME NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_status_remind` (`status`, `remind_at`),
  KEY `idx_memo` (`memo_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='提醒计划';

CREATE TABLE IF NOT EXISTS `memo_change_log` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `memo_id` BIGINT UNSIGNED NOT NULL,
  `sender_id` BIGINT UNSIGNED NOT NULL,
  `source` VARCHAR(16) NOT NULL COMMENT 'AGENT|VUE',
  `op` VARCHAR(16) NOT NULL COMMENT 'CREATE|UPDATE|CLOSE',
  `event_id` VARCHAR(64) NULL COMMENT '幂等键；人工改动为 NULL',
  `before_json` TEXT NULL,
  `after_json` TEXT NULL,
  `result_json` TEXT NULL,
  `conversation_id` VARCHAR(64) NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_event` (`event_id`),
  KEY `idx_memo` (`memo_id`),
  KEY `idx_sender_created` (`sender_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='备忘变更留痕 + Agent 幂等台账';

CREATE TABLE IF NOT EXISTS `collection_task` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `chat_id` BIGINT UNSIGNED NOT NULL,
  `task_type` VARCHAR(32) NOT NULL,
  `task_key` VARCHAR(128) NOT NULL,
  `window_start` DATETIME NULL,
  `status` VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  `replied_at` DATETIME NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_chat_task` (`chat_id`, `task_key`),
  KEY `idx_chat_status` (`chat_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='统一回复防重状态';

CREATE TABLE IF NOT EXISTS `intercept_rule` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `name` VARCHAR(64) NOT NULL,
  `keywords` VARCHAR(512) NULL,
  `regex` VARCHAR(512) NULL,
  `reply_template` TEXT NULL,
  `priority` INT NOT NULL DEFAULT 0,
  `enabled` TINYINT(1) NOT NULL DEFAULT 1,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_enabled_priority` (`enabled`, `priority`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='前置拦截规则';

CREATE TABLE IF NOT EXISTS `profile_fact` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `sender_id` BIGINT UNSIGNED NOT NULL,
  `fact_key` VARCHAR(64) NOT NULL,
  `fact_value` VARCHAR(512) NOT NULL,
  `confidence` DECIMAL(3,2) NULL,
  `source` VARCHAR(16) NOT NULL DEFAULT 'AUTO',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sender_key` (`sender_id`, `fact_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='事实记忆';

CREATE TABLE IF NOT EXISTS `style_profile` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `sender_id` BIGINT UNSIGNED NOT NULL,
  `profile_prompt` TEXT NOT NULL,
  `last_summarized_at` DATETIME NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sender` (`sender_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='风格画像';

CREATE TABLE IF NOT EXISTS `app_setting` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `setting_key` VARCHAR(64) NOT NULL,
  `setting_value` VARCHAR(512) NULL,
  `description` VARCHAR(255) NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_setting_key` (`setting_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='全局配置';

CREATE TABLE IF NOT EXISTS `file_task` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `task_ref` VARCHAR(64) NOT NULL,
  `sender_id` BIGINT UNSIGNED NOT NULL,
  `chat_id` BIGINT UNSIGNED NULL,
  `conversation_id` VARCHAR(64) NULL COMMENT 'Dify conversation_id',
  `file_name` VARCHAR(255) NULL,
  `request` TEXT NULL COMMENT '用户原始填写要求',
  `pending_request` TEXT NULL COMMENT '等待文件时挂起的要求',
  `status` VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  `missing` JSON NULL COMMENT 'Dify 判定缺失的字段',
  `confidence` DECIMAL(3,2) NULL COMMENT '填写置信度',
  `needs_confirm` TINYINT(1) NULL COMMENT '是否强制人工确认',
  `file_ref` VARCHAR(512) NULL COMMENT '输入文件引用',
  `output_ref` VARCHAR(512) NULL COMMENT '产物文件引用',
  `source_message_id` BIGINT UNSIGNED NULL COMMENT '源 QQ 消息 id',
  `idempotency_key` VARCHAR(128) NULL COMMENT '重复投递去重',
  `detail` JSON NULL,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_ref` (`task_ref`),
  KEY `idx_sender_status` (`sender_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='文件任务状态镜像';

CREATE TABLE IF NOT EXISTS `conversation_session` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `bot_uuid` VARCHAR(64) NOT NULL,
  `chat_type` VARCHAR(16) NOT NULL COMMENT 'private|group',
  `chat_id` BIGINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '私聊为0，群聊为群号',
  `sender_id` BIGINT UNSIGNED NOT NULL,
  `dify_conversation_id` VARCHAR(64) NOT NULL,
  `status` VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE|RESET',
  `last_active_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_session` (`bot_uuid`, `chat_type`, `chat_id`, `sender_id`),
  KEY `idx_sender` (`sender_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='QQ会话与Dify conversation_id映射';

CREATE TABLE IF NOT EXISTS `chat_setting` (
  `id`                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `chat_id`            BIGINT UNSIGNED NOT NULL COMMENT '群号；私聊存对方 sender_id',
  `kind`               VARCHAR(8)  NOT NULL COMMENT 'GROUP|PRIVATE',
  `chat_name`          VARCHAR(128) NULL,
  `auto_reply_enabled` TINYINT(1)  NOT NULL DEFAULT 0,
  `digest_enabled`     TINYINT(1)  NOT NULL DEFAULT 0,
  `created_at`         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_chat` (`chat_id`, `kind`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会话开关';

CREATE TABLE IF NOT EXISTS `chat_digest_item` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `chat_id`        BIGINT UNSIGNED NOT NULL,
  `chat_name`      VARCHAR(128) NULL,
  `period_start`   DATETIME NOT NULL,
  `period_end`     DATETIME NOT NULL,
  `summary`        VARCHAR(512) NOT NULL,
  `importance`     TINYINT      NOT NULL COMMENT '1-5',
  `keywords`       VARCHAR(255) NULL,
  `mentions_me`    TINYINT(1)   NOT NULL DEFAULT 0,
  `source_msg_ids` TEXT         NOT NULL COMMENT 'message_log.id，逗号分隔',
  `created_at`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_chat_period` (`chat_id`, `period_start`),
  KEY `idx_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='消息整理';

CREATE TABLE IF NOT EXISTS `contact` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `sender_id`  BIGINT UNSIGNED NOT NULL,
  `alias`      VARCHAR(64)  NOT NULL COMMENT '用户/模型认出来的称呼',
  `relation`   VARCHAR(64)  NULL COMMENT '同学|老师|室友…',
  `importance` TINYINT      NOT NULL DEFAULT 0 COMMENT '0=普通 1=一般 2=重要',
  `note`       VARCHAR(255) NULL,
  `source`     VARCHAR(8)   NOT NULL DEFAULT 'AUTO' COMMENT 'AUTO|USER',
  `created_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sender_alias` (`sender_id`, `alias`),
  KEY `idx_alias` (`alias`),
  KEY `idx_sender` (`sender_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='认识的人';

CREATE TABLE IF NOT EXISTS `memory_note` (
  `id`                BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `subject_sender_id` BIGINT UNSIGNED NULL COMMENT '主体；空=关于主人自己',
  `predicate`         VARCHAR(64)  NOT NULL COMMENT '受控词表',
  `value`             VARCHAR(255) NOT NULL,
  `happened_at`       DATETIME     NULL,
  `source_msg_ids`    VARCHAR(512) NOT NULL COMMENT '逗号分隔的 message_log.id',
  `confidence`        DECIMAL(3,2) NULL,
  `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_subject_pred` (`subject_sender_id`, `predicate`),
  KEY `idx_predicate` (`predicate`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='事实归纳';

CREATE TABLE IF NOT EXISTS `message_embedding` (
  `message_log_id` BIGINT UNSIGNED NOT NULL,
  `model`          VARCHAR(64) NOT NULL,
  `dim`            SMALLINT NOT NULL,
  `vector`         MEDIUMBLOB NOT NULL,
  `created_at`     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`message_log_id`,`model`),
  KEY `idx_model` (`model`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消息向量';

CREATE TABLE IF NOT EXISTS `ask_trace` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `question`       VARCHAR(512)  NOT NULL,
  `entities`       VARCHAR(512)  NULL,
  `queries`        VARCHAR(1024) NULL,
  `channels`       VARCHAR(128)  NULL,
  `candidate_cnt`  INT NOT NULL DEFAULT 0,
  `rounds`         TINYINT NOT NULL DEFAULT 1,
  `answer`         VARCHAR(1024) NULL,
  `degraded`       TINYINT NOT NULL DEFAULT 0,
  `latency_ms`     INT NULL,
  `created_at`     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `idx_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='一次问答的检索轨迹';