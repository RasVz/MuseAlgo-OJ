-- Serialize idempotent AC-history projection and rejudge rebuilds per user.
CREATE TABLE user_tag_rating_lock (
    user_id BIGINT NOT NULL,
    PRIMARY KEY (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
