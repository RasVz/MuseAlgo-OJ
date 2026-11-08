-- T8-3 D：评分/结算算法版本化并存（v1）。
-- 目的：历史结算保留其算法版本，后续新算法可并存；不在本迁移中重算任何历史成绩。

CREATE TABLE contest_settlement (
    contest_id BIGINT NOT NULL PRIMARY KEY COMMENT '比赛ID',
    algorithm_version VARCHAR(16) NOT NULL COMMENT '计分/榜单算法版本',
    rating_algorithm_version VARCHAR(16) NOT NULL COMMENT 'Rating算法版本',
    settlement_no INT NOT NULL DEFAULT 1 COMMENT '结算轮次（D阶段固定1，C链式重放递增）',
    finalized_at DATETIME NOT NULL COMMENT '固化时间',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='比赛结算版本记录';

-- 已固化历史比赛：标记为 v1，不做任何重算（用户确认不追溯）。
INSERT INTO contest_settlement (contest_id, algorithm_version, rating_algorithm_version, settlement_no, finalized_at)
SELECT id, 'v1', 'elo-v1', 1, rank_finalized_at FROM contest WHERE rank_finalized_at IS NOT NULL;

ALTER TABLE contest_rank
    ADD COLUMN algorithm_version VARCHAR(16) NOT NULL DEFAULT 'v1' COMMENT '成绩计算所用算法版本' AFTER participant_type;
