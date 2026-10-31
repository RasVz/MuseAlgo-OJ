-- 提交记录分享默认值：所有用户默认分享（shareSubmissions=true）
-- 之前依赖"缺失即默认分享"的隐式逻辑，这里把默认值显式写入 preferences JSON
UPDATE `user`
SET preferences = JSON_SET(COALESCE(preferences, JSON_OBJECT()), '$.shareSubmissions', TRUE)
WHERE preferences IS NULL OR JSON_EXTRACT(preferences, '$.shareSubmissions') IS NULL;
