-- ============================================================
-- MuseAlgo-OJ 数据库结构基线
-- ------------------------------------------------------------
-- 内容：全部表结构（无任何业务数据）+ Flyway 迁移历史记录。
-- 来源：由开发库 mysqldump --no-data 导出，并附上 flyway_schema_history
--       的 48 条迁移记录（V1-V6 为 BASELINE，V7-V53 为已应用）。
--
-- 为什么需要它：本项目 V1 起的 Flyway 迁移是「增量迁移」，其首条外键
-- 即引用 user 表，而 user 表属于迁移之前的既有基线 —— 空库直接跑 Flyway
-- 必然失败。因此空库必须先建立结构基线，Flyway 才能校验通过并跳过已应用迁移。
--
-- 用法：docker/mysql-init/ 已挂载到 MySQL 的 /docker-entrypoint-initdb.d，
--       数据卷为空时 Docker 会自动执行本文件。若数据卷已存在，需先 down -v。
-- 业务数据（演示账号、示例题目）另由 scripts/db-seed.sh 导入。
-- ============================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;


/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;
DROP TABLE IF EXISTS `ai_hint_trace`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ai_hint_trace` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `problem_id` bigint NOT NULL,
  `level` tinyint NOT NULL,
  `source` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'USER',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_hint_trace_user_time` (`user_id`,`created_at`),
  KEY `idx_hint_trace_problem` (`problem_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `ai_usage`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ai_usage` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL COMMENT '调用用户',
  `problem_id` bigint DEFAULT NULL COMMENT '关联题目（答疑场景）',
  `scene` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'ASK' COMMENT '场景：ASK=题目答疑，预留 REVIEW/PLAN 等',
  `model` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '模型名，如 deepseek-chat',
  `prompt_tokens` int NOT NULL DEFAULT '0' COMMENT '输入 token（估算或 API 返回）',
  `completion_tokens` int NOT NULL DEFAULT '0' COMMENT '输出 token',
  `total_tokens` int NOT NULL DEFAULT '0',
  `tokens_estimated` tinyint(1) NOT NULL DEFAULT '0' COMMENT '1=按字符估算，0=API 返回的精确值',
  `latency_ms` bigint NOT NULL DEFAULT '0' COMMENT '端到端耗时（含流式生成）',
  `status` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'SUCCESS/FAIL/QUOTA_REJECTED/CONTEST_BLOCKED',
  `detail` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '失败原因或拦截说明（截断）',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_ai_usage_user_time` (`user_id`,`created_at`),
  KEY `idx_ai_usage_status_time` (`status`,`created_at`)
) ENGINE=InnoDB AUTO_INCREMENT=36 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 调用用量流水';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `audit_log`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `audit_log` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL COMMENT '操作人用户 ID',
  `username` varchar(64) NOT NULL COMMENT '操作人用户名（冗余）',
  `module` varchar(64) NOT NULL COMMENT '操作模块（如 user/problem/contest）',
  `action` varchar(64) NOT NULL COMMENT '操作动作（如 delete_user/reset_password）',
  `method_name` varchar(255) NOT NULL COMMENT '执行的方法全限定名（类名.方法名）',
  `request_params` text COMMENT '方法入参（JSON 格式，超长自动截断）',
  `client_ip` varchar(45) DEFAULT NULL COMMENT '请求来源 IP',
  `execution_time` bigint DEFAULT NULL COMMENT '方法执行耗时（毫秒）',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '执行结果：1-成功 0-异常',
  `error_message` text COMMENT '异常信息（失败时记录）',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '日志创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_audit_user` (`user_id`),
  KEY `idx_audit_module` (`module`),
  KEY `idx_audit_create_time` (`create_time`)
) ENGINE=InnoDB AUTO_INCREMENT=71 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='操作审计日志表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `badge`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `badge` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(32) NOT NULL COMMENT '徽章名称 (如: 刷题狂魔)',
  `icon` varchar(255) NOT NULL COMMENT '图标/图片URL',
  `description` varchar(255) DEFAULT NULL COMMENT '获取条件描述',
  `condition_type` varchar(32) DEFAULT NULL COMMENT '触发条件类型 (如: ac_count)',
  `condition_value` int DEFAULT '0' COMMENT '触发阈值 (如: 100)',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=6 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系统徽章表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `class`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `class` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(64) NOT NULL COMMENT '班级名称',
  `teacher_id` bigint NOT NULL COMMENT '创建教师ID',
  `student_count` int NOT NULL DEFAULT '0' COMMENT '学生人数',
  `invite_code` varchar(10) DEFAULT NULL COMMENT '邀请码',
  `description` varchar(255) DEFAULT NULL COMMENT '简介',
  `school` varchar(64) DEFAULT NULL COMMENT '所属机构',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `is_deleted` tinyint(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_invite_code` (`invite_code`),
  KEY `idx_teacher` (`teacher_id`),
  KEY `idx_class_deleted` (`is_deleted`)
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='班级表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `class_courseware`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `class_courseware` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `class_id` bigint NOT NULL,
  `courseware_id` bigint NOT NULL COMMENT '课件ID',
  `sort` int DEFAULT '0' COMMENT '排序 (用于目录上下移动)',
  `is_visible` tinyint(1) DEFAULT '1' COMMENT '0-隐藏 1-显示 (默认显示)',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_class` (`class_id`),
  KEY `idx_courseware` (`courseware_id`)
) ENGINE=InnoDB AUTO_INCREMENT=6 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='班级课件关联';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `class_student`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `class_student` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `class_id` bigint NOT NULL,
  `student_id` bigint NOT NULL,
  `join_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `remark` varchar(255) DEFAULT NULL COMMENT '班级内备注',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_class_student` (`class_id`,`student_id`)
) ENGINE=InnoDB AUTO_INCREMENT=13 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='班级成员表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `comment`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `comment` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint NOT NULL COMMENT '评论者ID (谁发的)',
  `entity_id` bigint NOT NULL COMMENT '被评论对象的ID (例如动态ID、题目ID)',
  `entity_type` tinyint NOT NULL DEFAULT '1' COMMENT '评论类型 (1:动态 2:题目 3:题解) - 方便以后复用',
  `content` text NOT NULL COMMENT '评论内容',
  `parent_id` bigint DEFAULT '0' COMMENT '父评论ID (0表示这是一条根评论)',
  `reply_user_id` bigint DEFAULT '0' COMMENT '被回复者ID (如果是在回复某人)',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `is_deleted` tinyint(1) DEFAULT '0' COMMENT '逻辑删除',
  `like_count` int DEFAULT '0' COMMENT '点赞数',
  `reply_count` int DEFAULT '0' COMMENT '回复数',
  PRIMARY KEY (`id`),
  KEY `idx_entity` (`entity_id`,`entity_type`) USING BTREE COMMENT '查某个动态下的所有评论',
  KEY `idx_user` (`user_id`) USING BTREE COMMENT '查某人发过的评论'
) ENGINE=InnoDB AUTO_INCREMENT=27 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='评论表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `comment_like`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `comment_like` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint NOT NULL COMMENT '谁点的赞',
  `comment_id` bigint NOT NULL COMMENT '被点赞的评论ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '点赞时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_comment` (`user_id`,`comment_id`),
  KEY `idx_comment_id` (`comment_id`)
) ENGINE=InnoDB AUTO_INCREMENT=7 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='评论点赞关联表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `community_tag`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `community_tag` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `group_name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT '自定义标签',
  `enabled` tinyint(1) NOT NULL DEFAULT '1',
  `created_by` bigint DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_community_tag_name` (`name`),
  KEY `idx_community_tag_group` (`group_name`,`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='社区受控标签扩展';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `content_report`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `content_report` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `reporter_id` bigint NOT NULL,
  `post_id` bigint NOT NULL,
  `reason` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `detail` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RESOLVED/DISMISSED',
  `handler_id` bigint DEFAULT NULL,
  `handle_note` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `handled_at` datetime DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_reporter_post_pending` (`reporter_id`,`post_id`,`status`),
  KEY `idx_content_report_status_time` (`status`,`create_time`)
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='社区内容举报';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `contest`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `contest` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `title` varchar(255) NOT NULL COMMENT '赛事名称',
  `description` text COMMENT '赛事描述/规则',
  `start_time` datetime NOT NULL COMMENT '开始时间',
  `end_time` datetime NOT NULL COMMENT '结束时间',
  `duration` int NOT NULL COMMENT '时长(分钟)',
  `type` varchar(50) DEFAULT 'Public' COMMENT '类型: Official, Public, Private',
  `mode` varchar(16) NOT NULL DEFAULT 'ACM' COMMENT 'ACM / OI',
  `author_id` bigint DEFAULT NULL COMMENT '举办人ID',
  `author_name` varchar(100) DEFAULT NULL COMMENT '举办人名称',
  `participant_count` int DEFAULT '0' COMMENT '参赛人数',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `is_deleted` tinyint(1) DEFAULT '0' COMMENT '逻辑删除',
  `class_id` bigint DEFAULT NULL COMMENT '所属班级ID',
  `visible` tinyint(1) NOT NULL DEFAULT '0' COMMENT '0不可见，1可见',
  `plagiarism_checked` tinyint(1) NOT NULL DEFAULT '0' COMMENT '赛后自动查重是否已执行（0-未执行 1-已执行）',
  PRIMARY KEY (`id`),
  KEY `idx_author` (`author_id`),
  KEY `idx_time_range` (`start_time`,`end_time`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='竞赛表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `contest_rank`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `contest_rank` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `contest_id` bigint NOT NULL COMMENT '比赛ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `rank_num` int NOT NULL COMMENT '最终名次',
  `solved_count` int DEFAULT '0' COMMENT '解题数量',
  `penalty_time` int DEFAULT '0' COMMENT '罚时(分钟)',
  `total_score` int DEFAULT '0' COMMENT '总分(IOI赛制)',
  `rating_before` int DEFAULT NULL COMMENT '赛前Rating',
  `rating_after` int DEFAULT NULL COMMENT '赛后Rating',
  `award` varchar(50) DEFAULT NULL COMMENT '奖项(金牌/银牌/铜牌/优秀奖)',
  `is_visible` tinyint(1) DEFAULT '1' COMMENT '是否可见(0-隐藏,1-可见)',
  `is_deleted` tinyint(1) DEFAULT '0' COMMENT '是否逻辑删除(0-未删除,1-已删除)',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `create_by` bigint DEFAULT NULL COMMENT '创建人ID',
  `update_by` bigint DEFAULT NULL COMMENT '更新人ID',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_contest_user` (`contest_id`,`user_id`) COMMENT '比赛用户唯一',
  KEY `idx_contest_rank` (`contest_id`,`rank_num`) COMMENT '比赛排名查询',
  KEY `idx_user_contest` (`user_id`,`contest_id`) COMMENT '用户比赛查询',
  KEY `idx_create_time` (`create_time`) COMMENT '时间范围查询'
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='比赛排行榜';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `contest_user`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `contest_user` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `contest_id` bigint NOT NULL COMMENT '比赛ID',
  `user_id` bigint NOT NULL COMMENT '报名用户ID',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '报名时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_contest_user` (`contest_id`,`user_id`) COMMENT '确保同人同比赛只能报名一次',
  KEY `idx_user_id` (`user_id`) COMMENT '方便查询某个用户报名了哪些比赛'
) ENGINE=InnoDB AUTO_INCREMENT=12 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='比赛报名记录表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `courseware`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `courseware` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `teacher_id` bigint NOT NULL COMMENT '创建老师ID',
  `title` varchar(128) NOT NULL COMMENT '课件标题',
  `url` varchar(512) DEFAULT NULL COMMENT 'Slidev构建产物的访问地址 (供iframe加载)',
  `type` tinyint(1) NOT NULL DEFAULT '0' COMMENT '课件类型: 0-Slidev网页, 1-腾讯文档嵌入',
  `problem_list_id` bigint DEFAULT NULL COMMENT '关联的练习题单ID (供右侧栏显示)',
  `content` longtext COMMENT 'Slidev Markdown源码 (备份源码)',
  `description` varchar(255) DEFAULT NULL COMMENT '简介',
  `privacy` tinyint DEFAULT '0' COMMENT '0-私有, 1-公开',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `is_deleted` tinyint(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_teacher` (`teacher_id`)
) ENGINE=InnoDB AUTO_INCREMENT=10 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='课件资源表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `exam_answer`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `exam_answer` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `paper_id` bigint NOT NULL,
  `attempt_id` bigint DEFAULT NULL COMMENT '作答会话ID；历史答案为空',
  `question_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  `answer` varchar(255) DEFAULT NULL COMMENT '学生作答',
  `is_correct` tinyint(1) NOT NULL DEFAULT '0',
  `score` int NOT NULL DEFAULT '0',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_attempt_answer` (`attempt_id`,`question_id`)
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客观题作答与判分';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `exam_attempt`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `exam_attempt` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `paper_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING / SUBMITTED / TIMEOUT',
  `start_time` datetime NOT NULL,
  `end_time` datetime NOT NULL COMMENT 'start_time + duration；到时自动交卷',
  `submit_time` datetime DEFAULT NULL,
  `objective_score` int NOT NULL DEFAULT '0' COMMENT '客观题得分',
  `coding_score` int NOT NULL DEFAULT '0' COMMENT '编程题得分',
  `total_score` int NOT NULL DEFAULT '0',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_paper_user` (`paper_id`,`user_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='训练试卷作答会话';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `exam_paper`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `exam_paper` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `title` varchar(200) NOT NULL COMMENT '试卷标题，如 GESP 2级 2024-09 真题',
  `description` text COMMENT '试卷说明 / 备考建议',
  `category` varchar(32) NOT NULL DEFAULT 'MOCK' COMMENT 'GESP / CSP_J / CSP_S / NOIP / MOCK',
  `level` varchar(32) DEFAULT NULL COMMENT '等级/组别，如 GESP 2级、CSP-J',
  `duration_minutes` int NOT NULL DEFAULT '120' COMMENT '建议作答时长（分钟）',
  `total_score` int NOT NULL DEFAULT '100' COMMENT '试卷满分',
  `problem_count` int NOT NULL DEFAULT '0' COMMENT '题目数（冗余，随题目变更刷新）',
  `is_published` tinyint(1) NOT NULL DEFAULT '0' COMMENT '0=草稿，1=发布（训练 tab 可见）',
  `create_by` bigint DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `is_deleted` tinyint(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_category` (`category`),
  KEY `idx_published` (`is_published`)
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='训练试卷';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `exam_paper_problem`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `exam_paper_problem` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `paper_id` bigint NOT NULL,
  `problem_id` bigint NOT NULL,
  `problem_order` int NOT NULL DEFAULT '0' COMMENT '题号，从 1 开始',
  `score` int NOT NULL DEFAULT '0' COMMENT '该题分值',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_paper_problem` (`paper_id`,`problem_id`),
  KEY `idx_problem` (`problem_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='试卷-题目关联';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `exam_question`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `exam_question` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `paper_id` bigint NOT NULL,
  `question_type` varchar(16) NOT NULL COMMENT 'SINGLE / MULTIPLE / JUDGE',
  `statement` text NOT NULL COMMENT '题干',
  `options` json DEFAULT NULL COMMENT '选项 [{key,text}]；判断题可为空',
  `answer` varchar(255) NOT NULL COMMENT '标准答案：A / ABD / T / F',
  `explanation` text COMMENT '题目解析（判分后展示）',
  `score` int NOT NULL DEFAULT '0',
  `question_order` int NOT NULL DEFAULT '0',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_paper` (`paper_id`,`question_order`)
) ENGINE=InnoDB AUTO_INCREMENT=10 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='试卷客观题';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `flyway_schema_history`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `flyway_schema_history` (
  `installed_rank` int NOT NULL,
  `version` varchar(50) DEFAULT NULL,
  `description` varchar(200) NOT NULL,
  `type` varchar(20) NOT NULL,
  `script` varchar(1000) NOT NULL,
  `checksum` int DEFAULT NULL,
  `installed_by` varchar(100) NOT NULL,
  `installed_on` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `execution_time` int NOT NULL,
  `success` tinyint(1) NOT NULL,
  PRIMARY KEY (`installed_rank`),
  KEY `flyway_schema_history_s_idx` (`success`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `judge_task`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `judge_task` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `submission_id` bigint NOT NULL,
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING',
  `attempt_count` int unsigned NOT NULL DEFAULT '0',
  `scheduled_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `locked_at` datetime DEFAULT NULL,
  `completed_at` datetime DEFAULT NULL,
  `last_error` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_judge_task_submission` (`submission_id`),
  KEY `idx_judge_task_claim` (`status`,`scheduled_at`),
  CONSTRAINT `fk_judge_task_submission` FOREIGN KEY (`submission_id`) REFERENCES `submission` (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=338 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `metrics_event`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `metrics_event` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `event_day` date NOT NULL COMMENT '统计日（自然日）',
  `event_type` varchar(20) NOT NULL COMMENT '事件类型: route=路由切换 / route_slow=慢切换(>500ms) / api_fail=接口失败 / js_error=页面错误',
  `event_key` varchar(120) NOT NULL COMMENT '事件对象: 路由名 / 接口路径 / 错误消息(前200字)',
  `count` int NOT NULL DEFAULT '1' COMMENT '当日累计次数',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次记录时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_day_type_key` (`event_day`,`event_type`,`event_key`),
  KEY `idx_day_type` (`event_day`,`event_type`)
) ENGINE=InnoDB AUTO_INCREMENT=145 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='前端体验埋点聚合事件（匿名，无用户关联）';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `outbox_event`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `outbox_event` (
  `id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `aggregate_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `aggregate_id` bigint unsigned NOT NULL,
  `event_type` varchar(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `payload` json NOT NULL,
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING',
  `retry_count` int unsigned NOT NULL DEFAULT '0',
  `next_attempt_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `published_at` datetime DEFAULT NULL,
  `last_error` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_outbox_event_publish` (`status`,`next_attempt_at`),
  KEY `idx_outbox_event_aggregate` (`aggregate_type`,`aggregate_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `outbox_event_archive`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `outbox_event_archive` (
  `id` char(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `aggregate_type` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `aggregate_id` bigint unsigned NOT NULL,
  `event_type` varchar(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `payload` json NOT NULL,
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `retry_count` int unsigned NOT NULL DEFAULT '0',
  `published_at` datetime DEFAULT NULL,
  `last_error` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci,
  `created_at` datetime NOT NULL,
  `archived_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_outbox_archive_archived` (`archived_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `plagiarism_report`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `plagiarism_report` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `class_id` bigint DEFAULT NULL COMMENT '班级ID（比赛维度比对为 NULL）',
  `contest_id` bigint DEFAULT NULL COMMENT '比赛ID（比赛维度比对，班级比对为 NULL）',
  `problem_id` bigint NOT NULL,
  `submission_a_id` bigint NOT NULL,
  `submission_b_id` bigint NOT NULL,
  `user_a_id` bigint NOT NULL,
  `user_b_id` bigint NOT NULL,
  `similarity` double NOT NULL,
  `method` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'AST',
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_submission_pair` (`submission_a_id`,`submission_b_id`),
  KEY `idx_plagiarism_class` (`class_id`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `post`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `post` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `title` varchar(128) NOT NULL COMMENT '标题',
  `content` longtext NOT NULL COMMENT 'Markdown内容',
  `summary` varchar(256) DEFAULT NULL COMMENT '纯文本摘要(用于列表展示)',
  `author_id` bigint NOT NULL COMMENT '作者ID',
  `problem_id` bigint DEFAULT NULL COMMENT '关联题目ID',
  `contest_id` bigint DEFAULT NULL COMMENT '关联比赛ID',
  `class_id` bigint DEFAULT NULL COMMENT '关联班级ID',
  `type` tinyint NOT NULL DEFAULT '0',
  `category` varchar(32) DEFAULT 'algorithm',
  `tags` varchar(255) DEFAULT NULL COMMENT '如 ["DP", "Java"]',
  `is_top` tinyint(1) NOT NULL DEFAULT '0' COMMENT '置顶',
  `is_essence` tinyint(1) NOT NULL DEFAULT '0' COMMENT '加精',
  `views` int DEFAULT '0',
  `likes` int DEFAULT '0',
  `comment_count` int DEFAULT '0',
  `privacy` tinyint DEFAULT '0' COMMENT '0-公开, 1-私有',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `is_deleted` tinyint(1) NOT NULL DEFAULT '0',
  `images` varchar(2000) DEFAULT NULL COMMENT '动态图片列表，逗号分隔，最多9张',
  `dislikes` int NOT NULL DEFAULT '0' COMMENT '踩数',
  `favorites` int NOT NULL DEFAULT '0' COMMENT '收藏数',
  `score` double NOT NULL DEFAULT '0' COMMENT '热度缓存分(仅用于排序参考)',
  `hot_calculated_at` timestamp NULL DEFAULT NULL COMMENT '上次热度计算时间',
  `is_official` tinyint(1) NOT NULL DEFAULT '0' COMMENT '是否官方题解',
  PRIMARY KEY (`id`),
  KEY `idx_context` (`contest_id`,`problem_id`),
  KEY `idx_author` (`author_id`),
  KEY `idx_create_time` (`create_time`),
  KEY `idx_type_create` (`type`,`create_time`),
  KEY `idx_post_deleted` (`is_deleted`)
) ENGINE=InnoDB AUTO_INCREMENT=26 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='帖子表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `post_problem_relation`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `post_problem_relation` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `post_id` bigint NOT NULL,
  `problem_id` bigint NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_post_problem_relation` (`post_id`,`problem_id`),
  KEY `idx_post_problem_relation_problem` (`problem_id`,`post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识文章关联练习题';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `problem`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `problem` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '题目ID',
  `title` varchar(128) NOT NULL COMMENT '题目标题',
  `contest_id` int DEFAULT NULL COMMENT '关联的比赛ID',
  `content` longtext COMMENT '题目内容(Markdown)',
  `input_desc` text COMMENT '输入描述',
  `output_desc` text COMMENT '输出描述',
  `hint` text COMMENT '题目提示(Markdown)',
  `std_answer` text COMMENT 'AI 生成的标准答案（C++ 代码，教师可见）',
  `std_explanation` text COMMENT '标准答案的算法思路讲解',
  `std_tested` tinyint(1) NOT NULL DEFAULT '0' COMMENT '标准答案是否已通过测试数据验证（0-未测试，1-已测试）',
  `time_limit` int DEFAULT '1000' COMMENT '时间限制(ms)',
  `memory_limit` int DEFAULT '512' COMMENT '内存限制(MB)',
  `difficulty` int NOT NULL DEFAULT '800' COMMENT '难度分 800 - 3000',
  `tags` varchar(255) DEFAULT NULL COMMENT '标签JSON: ["DP","Array"]',
  `pass_count` int DEFAULT '0' COMMENT '通过次数',
  `total_count` int DEFAULT '0' COMMENT '提交总数',
  `test_case_count` int NOT NULL DEFAULT '0' COMMENT '测试用例数量',
  `author_id` bigint DEFAULT NULL COMMENT '出题人ID',
  `source` varchar(200) DEFAULT 'System' COMMENT '题目来源（如 LeetCode, NOIP）',
  `is_public` tinyint(1) DEFAULT '1' COMMENT '0-私有, 1-公开',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '题目创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `is_deleted` tinyint(1) NOT NULL DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_author` (`author_id`),
  KEY `idx_public_difficulty` (`is_public`,`difficulty`),
  KEY `idx_problem_deleted` (`is_deleted`)
) ENGINE=InnoDB AUTO_INCREMENT=99003 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='题目表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `problem_case`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `problem_case` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `problem_id` bigint NOT NULL COMMENT '题目ID',
  `case_order` int unsigned NOT NULL DEFAULT '1',
  `subtask_no` int unsigned NOT NULL DEFAULT '1' COMMENT '子任务编号，1 起；同题同组为一组',
  `input_file_path` varchar(512) DEFAULT NULL COMMENT '测试输入文件路径（相对于 testcase.base-path）',
  `output_file_path` varchar(512) DEFAULT NULL COMMENT '期望输出文件路径（相对于 testcase.base-path）',
  `score` int DEFAULT '10' COMMENT '该测试点的分数',
  `case_type` tinyint unsigned NOT NULL DEFAULT '0',
  `status` tinyint DEFAULT '1' COMMENT '0-禁用, 1-启用',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_pid` (`problem_id`),
  KEY `idx_problem_case_problem_order` (`problem_id`,`case_order`),
  KEY `idx_problem_subtask` (`problem_id`,`subtask_no`,`case_order`),
  CONSTRAINT `chk_problem_case_type` CHECK ((`case_type` in (0,1)))
) ENGINE=InnoDB AUTO_INCREMENT=40067 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='判题用例表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `problem_contest`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `problem_contest` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `contest_id` bigint NOT NULL COMMENT '竞赛ID',
  `problem_id` bigint NOT NULL COMMENT '题目ID',
  `problem_order` int DEFAULT '0' COMMENT '题目在竞赛中的顺序（如第1题、第2题）',
  `score` int DEFAULT '100' COMMENT '该题目在本场竞赛中的分值',
  `visible` tinyint(1) DEFAULT '1' COMMENT '是否可见：0-隐藏（赛前保密），1-可见',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `is_deleted` tinyint(1) DEFAULT '0' COMMENT '逻辑删除：0-未删除，1-已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_contest_problem` (`contest_id`,`problem_id`),
  KEY `idx_contest_id` (`contest_id`),
  KEY `idx_problem_id` (`problem_id`),
  KEY `idx_visible` (`visible`),
  KEY `idx_is_deleted` (`is_deleted`),
  KEY `idx_contest_visible` (`contest_id`,`visible`,`is_deleted`)
) ENGINE=InnoDB AUTO_INCREMENT=11 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='竞赛-题目关联表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `problem_list`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `problem_list` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `title` varchar(128) NOT NULL COMMENT '题单标题',
  `creator_id` bigint NOT NULL COMMENT '创建人ID (关联 sys_user)',
  `description` text COMMENT '题单简介 (支持 Markdown 格式)',
  `type` tinyint NOT NULL DEFAULT '1' COMMENT '类型: 1-个人题单, 2-官方题单, 3-班级题单, 4-班级作业',
  `class_id` bigint DEFAULT NULL COMMENT '所属班级ID (仅 type=2 或者 type=3 作业时有效)',
  `privacy` tinyint NOT NULL DEFAULT '0' COMMENT '权限: 0-私有, 1-公开',
  `start_time` datetime DEFAULT NULL COMMENT '作业开始时间',
  `end_time` datetime DEFAULT NULL COMMENT '作业截止时间',
  `problem_count` int NOT NULL DEFAULT '0' COMMENT '题目数量 (缓存)',
  `favorite_count` int NOT NULL DEFAULT '0' COMMENT '收藏/引用次数',
  `sort_order` int NOT NULL DEFAULT '0' COMMENT '手动排序值（越小越靠前，0=默认按创建时间）',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_creator` (`creator_id`) USING BTREE COMMENT '创建人索引',
  KEY `idx_class` (`class_id`) USING BTREE COMMENT '班级索引',
  KEY `idx_type` (`type`)
) ENGINE=InnoDB AUTO_INCREMENT=15 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='题单与作业主表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `problem_list_item`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `problem_list_item` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `list_id` bigint NOT NULL COMMENT '题单ID',
  `problem_id` bigint NOT NULL COMMENT '题目ID',
  `sort` int DEFAULT '0' COMMENT '排序(越小越前)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_list_problem` (`list_id`,`problem_id`) COMMENT '防止同一个单子里重复添加同一道题'
) ENGINE=InnoDB AUTO_INCREMENT=34 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='题单题目关联';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `problem_sample`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `problem_sample` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `problem_id` bigint NOT NULL COMMENT '题目ID',
  `sample_order` int unsigned NOT NULL DEFAULT '1' COMMENT '样例顺序，从 1 开始',
  `input` text COMMENT '样例输入',
  `output` text COMMENT '样例输出',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_problem_sample_problem_order` (`problem_id`,`sample_order`)
) ENGINE=InnoDB AUTO_INCREMENT=109 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='题目公开样例表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `problem_tag`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `problem_tag` (
  `problem_id` bigint unsigned NOT NULL,
  `tag_id` bigint unsigned NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`problem_id`,`tag_id`),
  KEY `idx_problem_tag_tag` (`tag_id`) COMMENT '画像统计核心索引：按 tag 反查题目'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='题目-标签关联';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `prompt_template`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `prompt_template` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `prompt_key` varchar(64) NOT NULL,
  `version` int NOT NULL DEFAULT '1',
  `content` text NOT NULL,
  `is_active` tinyint(1) NOT NULL DEFAULT '0',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_key_version` (`prompt_key`,`version`),
  KEY `idx_active` (`prompt_key`,`is_active`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='提示词模板版本';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `prompt_template_lock`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `prompt_template_lock` (
  `prompt_key` varchar(64) NOT NULL,
  PRIMARY KEY (`prompt_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='提示词模板版本并发锁';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `student_profile_snapshot`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `student_profile_snapshot` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `student_id` bigint unsigned NOT NULL,
  `class_id` bigint unsigned DEFAULT NULL,
  `period_start` date NOT NULL COMMENT '周期开始（周一）',
  `period_end` date NOT NULL COMMENT '周期结束（周日）',
  `snapshot_json` json NOT NULL COMMENT '完整画像 JSON（mastery/percentile/趋势）',
  `report_url` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '家长报告 PDF 的 COS 地址',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_profile_student_period` (`student_id`,`period_start`),
  KEY `idx_profile_class_period` (`class_id`,`period_start`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学生画像周快照';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `submission`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `submission` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `problem_id` bigint NOT NULL COMMENT '题目ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `language` varchar(32) NOT NULL DEFAULT 'java' COMMENT '语言',
  `score` int DEFAULT '0' COMMENT '分数',
  `status` tinyint DEFAULT '0' COMMENT '0-待判, 1-AC, 2-WA, 3-CE, 4-RE, 5-TLE',
  `run_time` int DEFAULT '0' COMMENT '耗时(ms)',
  `run_memory` int DEFAULT '0' COMMENT '内存(KB)',
  `error_message` text COMMENT '错误信息(编译错误/堆栈)',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `contest_id` bigint DEFAULT NULL COMMENT '比赛ID（非赛时为空）',
  `vp_attempt_id` bigint DEFAULT NULL COMMENT '虚拟赛会话ID；正式比赛提交为空',
  `judge_phase` varchar(24) NOT NULL DEFAULT 'QUEUED' COMMENT 'QUEUED/COMPILING/JUDGING/FINISHED/FAILED',
  PRIMARY KEY (`id`),
  KEY `idx_user` (`user_id`),
  KEY `idx_problem` (`problem_id`),
  KEY `idx_contest` (`contest_id`),
  KEY `idx_vp_attempt` (`vp_attempt_id`),
  KEY `idx_contest_vp_create` (`contest_id`,`vp_attempt_id`,`create_time`),
  KEY `idx_user_create` (`user_id`,`create_time`),
  KEY `idx_user_problem_create` (`user_id`,`problem_id`,`create_time`)
) ENGINE=InnoDB AUTO_INCREMENT=358 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='评测记录表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `submission_case_result`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `submission_case_result` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `submission_id` bigint NOT NULL,
  `problem_case_id` bigint DEFAULT NULL,
  `case_order` int unsigned NOT NULL,
  `status` tinyint unsigned NOT NULL,
  `run_time` int unsigned NOT NULL DEFAULT '0',
  `run_memory` int unsigned NOT NULL DEFAULT '0',
  `error_message` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_submission_case_result_submission_order` (`submission_id`,`case_order`),
  KEY `fk_submission_case_result_case` (`problem_case_id`),
  CONSTRAINT `fk_submission_case_result_case` FOREIGN KEY (`problem_case_id`) REFERENCES `problem_case` (`id`),
  CONSTRAINT `fk_submission_case_result_submission` FOREIGN KEY (`submission_id`) REFERENCES `submission` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `submission_code`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `submission_code` (
  `submission_id` bigint NOT NULL COMMENT '提交ID（1:1，主键即外键）',
  `code` longtext NOT NULL COMMENT '提交源码',
  PRIMARY KEY (`submission_id`),
  CONSTRAINT `fk_submission_code_submission` FOREIGN KEY (`submission_id`) REFERENCES `submission` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='评测提交源码（submission 拆表，1:1 懒加载）';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `submission_result`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `submission_result` (
  `submission_id` bigint NOT NULL,
  `status` tinyint unsigned NOT NULL,
  `score` int unsigned NOT NULL DEFAULT '0',
  `run_time` int unsigned NOT NULL DEFAULT '0',
  `run_memory` int unsigned NOT NULL DEFAULT '0',
  `error_message` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci,
  `judged_at` datetime NOT NULL,
  PRIMARY KEY (`submission_id`),
  CONSTRAINT `fk_submission_result_submission` FOREIGN KEY (`submission_id`) REFERENCES `submission` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `sys_permission`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_permission` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `code` varchar(96) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `name` varchar(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sys_permission_code` (`code`)
) ENGINE=InnoDB AUTO_INCREMENT=9 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `sys_role`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_role` (
  `id` tinyint unsigned NOT NULL,
  `code` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sys_role_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `sys_role_permission`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_role_permission` (
  `role_id` tinyint unsigned NOT NULL,
  `permission_id` bigint unsigned NOT NULL,
  PRIMARY KEY (`role_id`,`permission_id`),
  KEY `fk_sys_role_permission_permission` (`permission_id`),
  CONSTRAINT `fk_sys_role_permission_permission` FOREIGN KEY (`permission_id`) REFERENCES `sys_permission` (`id`),
  CONSTRAINT `fk_sys_role_permission_role` FOREIGN KEY (`role_id`) REFERENCES `sys_role` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `sys_user_role`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_user_role` (
  `user_id` bigint NOT NULL,
  `role_id` tinyint unsigned NOT NULL,
  PRIMARY KEY (`user_id`,`role_id`),
  KEY `fk_sys_user_role_role` (`role_id`),
  CONSTRAINT `fk_sys_user_role_role` FOREIGN KEY (`role_id`) REFERENCES `sys_role` (`id`),
  CONSTRAINT `fk_sys_user_role_user` FOREIGN KEY (`user_id`) REFERENCES `user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `system_config`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `system_config` (
  `config_key` varchar(128) NOT NULL,
  `config_value` varchar(512) NOT NULL,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`config_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系统配置（DB 覆盖 yml 默认值）';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `tag`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `tag` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `code` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '程序标识，如 dp_knapsack；唯一',
  `name` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '展示名，如 背包DP',
  `parent_id` bigint unsigned DEFAULT NULL COMMENT '父标签；NULL=知识域，指向知识域=知识点',
  `dimension` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '知识域: ALGORITHM/DATA_STRUCTURE/MATH/GRAPH/STRING',
  `sort_order` int NOT NULL DEFAULT '0' COMMENT '排序序号',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tag_code` (`code`),
  KEY `idx_tag_parent` (`parent_id`),
  KEY `idx_tag_dimension` (`dimension`)
) ENGINE=InnoDB AUTO_INCREMENT=83 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='标签字典（三层知识图谱）';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `user`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `username` varchar(64) NOT NULL COMMENT '账号(唯一)',
  `password` varchar(128) NOT NULL COMMENT '加密密码',
  `nickname` varchar(64) NOT NULL COMMENT '昵称',
  `role` tinyint NOT NULL DEFAULT '1' COMMENT '1-学生, 2-老师, 3-管理员',
  `is_banned` tinyint(1) NOT NULL DEFAULT '0' COMMENT '1=禁用',
  `email` varchar(64) DEFAULT NULL COMMENT '邮箱',
  `avatar` text COMMENT '头像URL',
  `real_name` varchar(32) DEFAULT NULL COMMENT '真实姓名(实名认证用)',
  `school` varchar(64) DEFAULT NULL COMMENT '学校/机构',
  `major` varchar(64) DEFAULT NULL COMMENT '专业/年级',
  `motto` varchar(255) DEFAULT NULL COMMENT '个性签名(Signature)',
  `blog` varchar(255) DEFAULT NULL COMMENT '个人博客链接',
  `github` varchar(255) DEFAULT NULL COMMENT 'Github链接',
  `preferences` json DEFAULT NULL COMMENT '用户偏好设置(JSON)',
  `level` int DEFAULT '1' COMMENT '用户等级',
  `experience` int DEFAULT '0' COMMENT '经验值(通过题目/比赛获得)',
  `follower_count` int DEFAULT '0' COMMENT '粉丝数',
  `following_count` int DEFAULT '0' COMMENT '关注数',
  `like_count` int DEFAULT '0' COMMENT '获赞数',
  `submit_count` int DEFAULT '0' COMMENT '提交总数',
  `accepted_count` int DEFAULT '0' COMMENT '通过总数',
  `rating` int DEFAULT '1200' COMMENT '竞赛积分',
  `analysis` json DEFAULT NULL COMMENT '能力值分析缓存',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `last_login_time` datetime DEFAULT NULL COMMENT '最后一次登录时间',
  `is_deleted` tinyint(1) DEFAULT '0',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`),
  UNIQUE KEY `uk_email` (`email`)
) ENGINE=InnoDB AUTO_INCREMENT=113 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户核心表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `user_badge`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_badge` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `badge_id` bigint NOT NULL,
  `obtain_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '获得时间',
  `is_wearing` tinyint(1) DEFAULT '0' COMMENT '是否佩戴展示',
  PRIMARY KEY (`id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB AUTO_INCREMENT=8 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户持有徽章表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `user_code_draft`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_code_draft` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `problem_id` bigint NOT NULL COMMENT '题目ID',
  `language` varchar(20) NOT NULL COMMENT '编程语言 (如 cpp, java, python)',
  `code` text COMMENT '代码内容',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_problem_lang` (`user_id`,`problem_id`,`language`) COMMENT '确保同人同题同语言只有一条草稿',
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户代码草稿表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `user_favorite`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_favorite` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL COMMENT '收藏者ID',
  `target_id` bigint NOT NULL COMMENT '被收藏内容ID',
  `target_type` tinyint NOT NULL COMMENT '目标类型: 1-题目, 2-题单, 3-比赛, 4-动态',
  `status` tinyint NOT NULL DEFAULT '1' COMMENT '收藏状态: 1-当前已收藏, 0-当前已取消',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次收藏时间（不变）',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后状态变更时间',
  `cancel_time` datetime DEFAULT NULL COMMENT '最近一次取消时间（status=0时有值，恢复收藏时置NULL）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_target` (`user_id`,`target_type`,`target_id`),
  KEY `idx_user_status` (`user_id`,`status`,`target_type`,`update_time`),
  KEY `idx_target_status` (`target_id`,`target_type`,`status`)
) ENGINE=InnoDB AUTO_INCREMENT=29 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='通用收藏表（单记录状态机设计：取消即更新，不保留历史）';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `user_like`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_like` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL,
  `post_id` bigint NOT NULL,
  `action_type` tinyint NOT NULL COMMENT '1点赞 2踩',
  `create_time` timestamp NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_post` (`user_id`,`post_id`,`action_type`),
  KEY `idx_post_type` (`post_id`,`action_type`)
) ENGINE=InnoDB AUTO_INCREMENT=23 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `user_notification`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_notification` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL COMMENT '接收人',
  `actor_id` bigint DEFAULT NULL COMMENT '触发人',
  `type` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'COMMENT/REPLY/LIKE/FAVORITE/FOLLOW_POST/CONTEST_REMINDER/HOMEWORK_REMINDER',
  `target_type` varchar(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'post/contest/homework',
  `target_id` bigint DEFAULT NULL,
  `title` varchar(160) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
  `content` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `is_read` tinyint(1) NOT NULL DEFAULT '0',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `read_time` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_notification_user_read_time` (`user_id`,`is_read`,`create_time`),
  KEY `idx_notification_target` (`target_type`,`target_id`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='站内通知';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `user_problem_review`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_problem_review` (
  `user_id` bigint NOT NULL,
  `problem_id` bigint NOT NULL,
  `status` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'REVIEW' COMMENT 'REVIEW/MASTERED',
  `reason` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'REPEATED_WA',
  `first_flagged_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `last_reviewed_at` datetime DEFAULT NULL,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`user_id`,`problem_id`),
  KEY `idx_user_problem_review_status` (`user_id`,`status`,`updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户错题复习计划';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `user_rating`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_rating` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `user_id` bigint NOT NULL COMMENT '用户ID',
  `contest_id` bigint NOT NULL COMMENT '比赛ID',
  `rating` int NOT NULL COMMENT '变动后的Rating值(用于画图)',
  `rating_change` int NOT NULL COMMENT '本次变动幅度(如 +50, -20)',
  `rank` int DEFAULT NULL COMMENT '比赛排名',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间',
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`) USING BTREE COMMENT '方便查询某人的历史',
  KEY `idx_contest` (`contest_id`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户Rating变化记录表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `user_relation`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_relation` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `follower_id` bigint NOT NULL COMMENT '关注者ID (粉丝)',
  `followed_id` bigint NOT NULL COMMENT '被关注者ID (博主)',
  `create_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_follow` (`follower_id`,`followed_id`),
  KEY `idx_followed` (`followed_id`)
) ENGINE=InnoDB AUTO_INCREMENT=11 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户关注关系表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `user_tag_rating`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_tag_rating` (
  `user_id` bigint unsigned NOT NULL,
  `tag_id` bigint unsigned NOT NULL,
  `rating` int NOT NULL DEFAULT '800' COMMENT '当前 ELO 评分',
  `problem_count` int NOT NULL DEFAULT '0' COMMENT '该标签下 AC 的题目数',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`user_id`,`tag_id`),
  KEY `idx_user_tag_rating_user` (`user_id`),
  KEY `idx_user_tag_rating_tag` (`tag_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户标签ELO评分表';
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `vp_attempt`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `vp_attempt` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `contest_id` bigint NOT NULL,
  `user_id` bigint NOT NULL,
  `status` varchar(16) NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING / FINISHED',
  `start_time` datetime NOT NULL,
  `end_time` datetime NOT NULL COMMENT 'start + contest.duration',
  `submit_time` datetime DEFAULT NULL,
  `score` int NOT NULL DEFAULT '0',
  `solved_count` int NOT NULL DEFAULT '0' COMMENT 'ACM 模式解题数',
  `penalty_time` int NOT NULL DEFAULT '0' COMMENT 'ACM 模式罚时（分钟）',
  `rank_num` int DEFAULT NULL,
  `total_participants` int DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_contest_user` (`contest_id`,`user_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='虚拟赛作答会话';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;


-- ---------- Flyway 迁移历史（使 Flyway 校验通过并跳过已应用迁移）----------
INSERT INTO `flyway_schema_history` (`installed_rank`, `version`, `description`, `type`, `script`, `checksum`, `installed_by`, `installed_on`, `execution_time`, `success`) VALUES (1,'6','<< Flyway Baseline >>','BASELINE','<< Flyway Baseline >>',NULL,'root','2026-07-22 11:42:48',0,1);
INSERT INTO `flyway_schema_history` (`installed_rank`, `version`, `description`, `type`, `script`, `checksum`, `installed_by`, `installed_on`, `execution_time`, `success`) VALUES (2,'7','testcase file storage','SQL','V7__testcase_file_storage.sql',-875305678,'root','2026-07-22 11:42:49',13,1);
INSERT INTO `flyway_schema_history` (`installed_rank`, `version`, `description`, `type`, `script`, `checksum`, `installed_by`, `installed_on`, `execution_time`, `success`) VALUES (3,'8','drop old testcase columns','SQL','V8__drop_old_testcase_columns.sql',536255193,'root','2026-07-22 11:42:49',9,1);
INSERT INTO `flyway_schema_history` (`installed_rank`, `version`, `description`, `type`, `script`, `checksum`, `installed_by`, `installed_on`, `execution_time`, `success`) VALUES (4,'9','add dp dimension','SQL','V9__add_dp_dimension.sql',-412922828,'root','2026-07-22 17:27:33',11,1);
INSERT INTO `flyway_schema_history` (`installed_rank`, `version`, `description`, `type`, `script`, `checksum`, `installed_by`, `installed_on`, `execution_time`, `success`) VALUES (5,'10','user tag rating','SQL','V10__user_tag_rating.sql',653096383,'root','2026-07-22 17:27:33',14,1);
INSERT INTO `flyway_schema_history` (`installed_rank`, `version`, `description`, `type`, `script`, `checksum`, `installed_by`, `installed_on`, `execution_time`, `success`) VALUES (6,'11','post problem relations','SQL','V11__post_problem_relations.sql',-723261224,'root','2026-07-25 19:08:22',53,1);
INSERT INTO `flyway_schema_history` (`installed_rank`, `version`, `description`, `type`, `script`, `checksum`, `installed_by`, `installed_on`, `execution_time`, `success`) VALUES (7,'12','submission judge phase','SQL','V12__submission_judge_phase.sql',-727283217,'root','2026-07-25 19:08:23',129,1);
INSERT INTO `flyway_schema_history` (`installed_rank`, `version`, `description`, `type`, `script`, `checksum`, `installed_by`, `installed_on`, `execution_time`, `success`) VALUES (8,'13','user problem review','SQL','V13__user_problem_review.sql',291597897,'root','2026-07-25 19:08:23',13,1);
INSERT INTO `flyway_schema_history` (`installed_rank`, `version`, `description`, `type`, `script`, `checksum`, `installed_by`, `installed_on`, `execution_time`, `success`) VALUES (9,'14','notifications and content governance','SQL','V14__notifications_and_content_governance.sql',-139107035,'root','2026-07-25 19:08:23',24,1);
INSERT INTO `flyway_schema_history` (`installed_rank`, `version`, `description`, `type`, `script`, `checksum`, `installed_by`, `installed_on`, `execution_time`, `success`) VALUES (10,'15','add problem test case count','SQL','V15__add_problem_test_case_count.sql',2026096309,'root','2026-07-30 12:18:10',427,1);
INSERT INTO `flyway_schema_history` (`installed_rank`, `version`, `description`, `type`, `script`, `checksum`, `installed_by`, `installed_on`, `execution_time`, `success`) VALUES (11,'16','problem samples','SQL','V16__problem_samples.sql',-390616815,'root','2026-07-31 08:03:41',318,1);
INSERT INTO `flyway_schema_history` (`installed_rank`, `version`, `description`, `type`, `script`, `checksum`, `installed_by`, `installed_on`, `execution_time`, `success`) VALUES (12,'17','default share submissions','SQL','V17__default_share_submissions.sql',-1334178239,'root','2026-07-31 08:56:33',28,1);
INSERT INTO `flyway_schema_history` (`installed_rank`, `version`, `description`, `type`, `script`, `checksum`, `installed_by`, `installed_on`, `execution_time`, `success`) VALUES (13,'18','widen avatar and default uploads','SQL','V18__widen_avatar_and_default_uploads.sql',-1585328218,'root','2026-07-31 09:28:50',181,1);
INSERT INTO `flyway_schema_history` (`installed_rank`, `version`, `description`, `type`, `script`, `checksum`, `installed_by`, `installed_on`, `execution_time`, `success`) VALUES (14,'19','ai usage','SQL','V19__ai_usage.sql',1154643457,'root','2026-08-01 11:24:55',138,1);
INSERT INTO `flyway_schema_history` (`installed_rank`, `version`, `description`, `type`, `script`, `checksum`, `installed_by`, `installed_on`, `execution_time`, `success`) VALUES (15,'20','oi subtask and contest mode','SQL','V20__oi_subtask_and_contest_mode.sql',-1734898004,'root','2026-08-01 16:00:34',404,1);
INSERT INTO `flyway_schema_history` (`installed_rank`, `version`, `description`, `type`, `script`, `checksum`, `installed_by`, `installed_on`, `execution_time`, `success`) VALUES (16,'21','exam paper','SQL','V21__exam_paper.sql',1742093142,'root','2026-08-01 16:34:00',114,1);
INSERT INTO `flyway_schema_history` (`installed_rank`, `version`, `description`, `type`, `script`, `checksum`, `installed_by`, `installed_on`, `execution_time`, `success`) VALUES (17,'22','exam objective questions','SQL','V22__exam_objective_questions.sql',-2101648897,'root','2026-08-01 17:00:36',100,1);
INSERT INTO `flyway_schema_history` (`installed_rank`, `version`, `description`, `type`, `script`, `checksum`, `installed_by`, `installed_on`, `execution_time`, `success`) VALUES (18,'23','exam attempt','SQL','V23__exam_attempt.sql',267198317,'root','2026-08-01 17:24:12',71,1);
INSERT INTO `flyway_schema_history` (`installed_rank`, `version`, `description`, `type`, `script`, `checksum`, `installed_by`, `installed_on`, `execution_time`, `success`) VALUES (19,'24','admin support','SQL','V24__admin_support.sql',-911748136,'root','2026-08-01 17:42:45',184,1);
INSERT INTO `flyway_schema_history` (`installed_rank`, `version`, `description`, `type`, `script`, `checksum`, `installed_by`, `installed_on`, `execution_time`, `success`) VALUES (20,'25','system config and prompt template','SQL','V25__system_config_and_prompt_template.sql',1811014920,'root','2026-08-01 18:23:09',109,1);
INSERT INTO `flyway_schema_history` (`installed_rank`, `version`, `description`, `type`, `script`, `checksum`, `installed_by`, `installed_on`, `execution_time`, `success`) VALUES (21,'26','vp attempt','SQL','V26__vp_attempt.sql',-1756011335,'root','2026-08-02 04:52:00',86,1);
INSERT INTO `flyway_schema_history` (`installed_rank`, `version`, `description`, `type`, `script`, `checksum`, `installed_by`, `installed_on`, `execution_time`, `success`) VALUES (22,'27','user last login','SQL','V27__user_last_login.sql',1056730365,'root','2026-08-02 05:29:47',104,1);
INSERT INTO `flyway_schema_history` (`installed_rank`, `version`, `description`, `type`, `script`, `checksum`, `installed_by`, `installed_on`, `execution_time`, `success`) VALUES (23,'28','submission context and exam attempt answers','SQL','V28__submission_context_and_exam_attempt_answers.sql',351538502,'root','2026-08-02 16:27:17',414,1);
INSERT INTO `flyway_schema_history` (`installed_rank`, `version`, `description`, `type`, `script`, `checksum`, `installed_by`, `installed_on`, `execution_time`, `success`) VALUES (24,'29','backfill exam answer attempt','SQL','V29__backfill_exam_answer_attempt.sql',-1303923430,'root','2026-08-02 16:27:17',5,1);
INSERT INTO `flyway_schema_history` (`installed_rank`, `version`, `description`, `type`, `script`, `checksum`, `installed_by`, `installed_on`, `execution_time`, `success`) VALUES (25,'30','prompt template lock','SQL','V30__prompt_template_lock.sql',2083254358,'root','2026-08-02 16:27:17',8,1);
INSERT INTO `flyway_schema_history` (`installed_rank`, `version`, `description`, `type`, `script`, `checksum`, `installed_by`, `installed_on`, `execution_time`, `success`) VALUES (26,'31','game gamification','SQL','V31__game_gamification.sql',1691031213,'root','2026-08-05 08:43:01',109,1);
INSERT INTO `flyway_schema_history` (`installed_rank`, `version`, `description`, `type`, `script`, `checksum`, `installed_by`, `installed_on`, `execution_time`, `success`) VALUES (27,'32','game standard answers','SQL','V32__game_standard_answers.sql',-572641905,'root','2026-08-05 11:48:06',137,1);
INSERT INTO `flyway_schema_history` (`installed_rank`, `version`, `description`, `type`, `script`, `checksum`, `installed_by`, `installed_on`, `execution_time`, `success`) VALUES (28,'33','game knapsack level','SQL','V33__game_knapsack_level.sql',1866237427,'root','2026-08-05 14:31:11',51,1);
INSERT INTO `flyway_schema_history` (`installed_rank`, `version`, `description`, `type`, `script`, `checksum`, `installed_by`, `installed_on`, `execution_time`, `success`) VALUES (29,'34','game base conversion','SQL','V34__game_base_conversion.sql',-912546719,'root','2026-08-06 07:20:13',50,1);
INSERT INTO `flyway_schema_history` (`installed_rank`, `version`, `description`, `type`, `script`, `checksum`, `installed_by`, `installed_on`, `execution_time`, `success`) VALUES (30,'35','game dfs','SQL','V35__game_dfs.sql',-1340596781,'root','2026-08-06 08:50:13',40,1);
INSERT INTO `flyway_schema_history` (`installed_rank`, `version`, `description`, `type`, `script`, `checksum`, `installed_by`, `installed_on`, `execution_time`, `success`) VALUES (31,'36','game bfs','SQL','V36__game_bfs.sql',-465788290,'root','2026-08-06 09:29:37',43,1);
INSERT INTO `flyway_schema_history` (`installed_rank`, `version`, `description`, `type`, `script`, `checksum`, `installed_by`, `installed_on`, `execution_time`, `success`) VALUES (32,'37','problem std answer','SQL','V37__problem_std_answer.sql',-1174625755,'root','2026-08-06 12:55:27',105,1);
INSERT INTO `flyway_schema_history` (`installed_rank`, `version`, `description`, `type`, `script`, `checksum`, `installed_by`, `installed_on`, `execution_time`, `success`) VALUES (33,'38','game lesson content','SQL','V38__game_lesson_content.sql',983154793,'root','2026-08-07 04:35:32',90,1);
INSERT INTO `flyway_schema_history` (`installed_rank`, `version`, `description`, `type`, `script`, `checksum`, `installed_by`, `installed_on`, `execution_time`, `success`) VALUES (34,'39','game level courseware','SQL','V39__game_level_courseware.sql',-369124189,'root','2026-08-07 04:35:32',9,1);
INSERT INTO `flyway_schema_history` (`installed_rank`, `version`, `description`, `type`, `script`, `checksum`, `installed_by`, `installed_on`, `execution_time`, `success`) VALUES (35,'40','game level code template','SQL','V40__game_level_code_template.sql',85999122,'root','2026-08-07 07:31:47',50,1);
INSERT INTO `flyway_schema_history` (`installed_rank`, `version`, `description`, `type`, `script`, `checksum`, `installed_by`, `installed_on`, `execution_time`, `success`) VALUES (36,'41','rename brand musi to ruisi','SQL','V41__rename_brand_musi_to_ruisi.sql',287836470,'root','2026-08-13 13:16:45',10,1);
INSERT INTO `flyway_schema_history` (`installed_rank`, `version`, `description`, `type`, `script`, `checksum`, `installed_by`, `installed_on`, `execution_time`, `success`) VALUES (37,'42','submission performance indexes','SQL','V42__submission_performance_indexes.sql',1936668041,'root','2026-08-13 15:15:54',129,1);
INSERT INTO `flyway_schema_history` (`installed_rank`, `version`, `description`, `type`, `script`, `checksum`, `installed_by`, `installed_on`, `execution_time`, `success`) VALUES (38,'43','outbox archive','SQL','V43__outbox_archive.sql',-220569015,'root','2026-08-14 11:02:17',33,1);
INSERT INTO `flyway_schema_history` (`installed_rank`, `version`, `description`, `type`, `script`, `checksum`, `installed_by`, `installed_on`, `execution_time`, `success`) VALUES (39,'44','ai hint trace','SQL','V44__ai_hint_trace.sql',-670789930,'root','2026-08-14 11:02:17',5,1);
INSERT INTO `flyway_schema_history` (`installed_rank`, `version`, `description`, `type`, `script`, `checksum`, `installed_by`, `installed_on`, `execution_time`, `success`) VALUES (40,'45','plagiarism report','SQL','V45__plagiarism_report.sql',131820694,'root','2026-08-14 11:02:17',7,1);
INSERT INTO `flyway_schema_history` (`installed_rank`, `version`, `description`, `type`, `script`, `checksum`, `installed_by`, `installed_on`, `execution_time`, `success`) VALUES (41,'46','courseware type','SQL','V46__courseware_type.sql',359480815,'root','2026-08-14 11:02:17',29,1);
INSERT INTO `flyway_schema_history` (`installed_rank`, `version`, `description`, `type`, `script`, `checksum`, `installed_by`, `installed_on`, `execution_time`, `success`) VALUES (42,'47','p1 health indexes','SQL','V47__p1_health_indexes.sql',-1118554352,'root','2026-08-14 14:40:32',79,1);
INSERT INTO `flyway_schema_history` (`installed_rank`, `version`, `description`, `type`, `script`, `checksum`, `installed_by`, `installed_on`, `execution_time`, `success`) VALUES (43,'48','plagiarism m2','SQL','V48__plagiarism_m2.sql',1114855036,'root','2026-08-14 16:22:21',141,1);
INSERT INTO `flyway_schema_history` (`installed_rank`, `version`, `description`, `type`, `script`, `checksum`, `installed_by`, `installed_on`, `execution_time`, `success`) VALUES (44,'49','problem std tested','SQL','V49__problem_std_tested.sql',-800191966,'root','2026-08-15 06:13:34',72,1);
INSERT INTO `flyway_schema_history` (`installed_rank`, `version`, `description`, `type`, `script`, `checksum`, `installed_by`, `installed_on`, `execution_time`, `success`) VALUES (45,'50','problem list sort order','SQL','V50__problem_list_sort_order.sql',-352504009,'root','2026-08-15 06:13:34',9,1);
INSERT INTO `flyway_schema_history` (`installed_rank`, `version`, `description`, `type`, `script`, `checksum`, `installed_by`, `installed_on`, `execution_time`, `success`) VALUES (46,'51','metrics event','SQL','V51__metrics_event.sql',2118730629,'root','2026-08-15 06:13:34',3,1);
INSERT INTO `flyway_schema_history` (`installed_rank`, `version`, `description`, `type`, `script`, `checksum`, `installed_by`, `installed_on`, `execution_time`, `success`) VALUES (47,'52','submission code split','SQL','V52__submission_code_split.sql',-722060647,'root','2026-08-15 06:13:35',30,1);
INSERT INTO `flyway_schema_history` (`installed_rank`, `version`, `description`, `type`, `script`, `checksum`, `installed_by`, `installed_on`, `execution_time`, `success`) VALUES (48,'53','drop game','SQL','V53__drop_game.sql',139919282,'root','2026-10-12 12:54:13',46,1);
