ALTER TABLE `user`
    ADD COLUMN `credential_version` BIGINT NOT NULL DEFAULT 0
        COMMENT 'Incremented on password changes to invalidate previously issued tokens';
