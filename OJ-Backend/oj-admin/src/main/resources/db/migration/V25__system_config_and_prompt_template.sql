-- ============================================================
-- 第三批：AI 配置 DB 化 + 提示词模板版本管理
-- ============================================================

-- 1) 系统配置：DB 覆盖 yml 默认值（AI 配额/比赛拦截/模型/等第阈值等）
CREATE TABLE IF NOT EXISTS system_config (
    config_key   VARCHAR(128) NOT NULL,
    config_value VARCHAR(512) NOT NULL,
    updated_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统配置（DB 覆盖 yml 默认值）';

-- 2) 提示词模板版本（版本化 + active 切换）
CREATE TABLE IF NOT EXISTS prompt_template (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    prompt_key VARCHAR(64) NOT NULL,
    version    INT         NOT NULL DEFAULT 1,
    content    TEXT        NOT NULL,
    is_active  TINYINT(1)  NOT NULL DEFAULT 0,
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_key_version (prompt_key, version),
    KEY idx_active (prompt_key, is_active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='提示词模板版本';
