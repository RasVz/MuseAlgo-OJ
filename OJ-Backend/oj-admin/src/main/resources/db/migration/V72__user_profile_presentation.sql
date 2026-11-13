-- Optional profile presentation; existing users keep the default cover and empty introduction.
ALTER TABLE `user`
    ADD COLUMN `cover_url` VARCHAR(1024) NULL,
    ADD COLUMN `cover_position` INT NOT NULL DEFAULT 50,
    ADD COLUMN `introduction` MEDIUMTEXT NULL;
