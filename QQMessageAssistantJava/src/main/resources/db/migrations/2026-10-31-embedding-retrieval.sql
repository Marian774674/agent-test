-- F4+ 智能检索数据底座：消息向量表、问答轨迹表、message_log 的中文全文索引。
-- 幂等：重复执行不报错（表用 IF NOT EXISTS，索引先查 information_schema）。
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

SET @has_ft = (SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 'message_log' AND index_name = 'ft_content');
SET @sql = IF(@has_ft = 0,
  'ALTER TABLE `message_log` ADD FULLTEXT INDEX `ft_content` (`content`) WITH PARSER ngram',
  'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;
