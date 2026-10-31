CREATE TABLE IF NOT EXISTS prompt_template_lock (
    prompt_key VARCHAR(64) NOT NULL,
    PRIMARY KEY (prompt_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='提示词模板版本并发锁';
