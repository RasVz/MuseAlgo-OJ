-- 品牌改名：慕思 -> 睿思（2026-08 用户拍板，规避「母死学院」谐音）
-- 慕思学院 -> 睿思学院；慕思星球 -> 睿思星球；user.school 存量「算法慕思学院」->「算法睿思学院」
-- 幂等：REPLACE 对不匹配行无副作用，可安全重放
UPDATE user SET school = REPLACE(school, '慕思', '睿思') WHERE school LIKE '%慕思%';
