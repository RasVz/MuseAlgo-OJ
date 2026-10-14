package top.everblue.oj.common.runner;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import top.everblue.oj.system.entity.User;
import top.everblue.oj.system.mapper.UserMapper;

import java.util.List;

/**
 * 项目启动时，将数据库中的积分【强制同步】到 Redis
 */
@Slf4j
@Component
public class RankInitRunner implements ApplicationRunner {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private static final String RANK_KEY = "oj:rank:rating";

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try {
            warmUp(args);
        } catch (Exception e) {
            // Redis/MySQL 是运行时依赖，基础 Web 容器不应因预热失败而无法启动。
            log.warn("排行榜预热跳过：{}", e.getMessage());
        }
    }

    private void warmUp(ApplicationArguments args) {
        log.info("-------------------------------------");
        log.info("正在进行排行榜数据预热...");

        // 1. 【核心修改】先删除旧的 Redis Key，防止有脏数据残留
        // 这样可以保证 Redis 和 MySQL 绝对同步 (比如你在 MySQL 删了人，这里也会同步删掉)
        redisTemplate.delete(RANK_KEY);

        // 2. 从 MySQL 查出所有用户
        // (注：如果用户量达到几十万，这里需要分页查，或者只查最近活跃用户)
        List<User> userList = userMapper.selectList(null);

        if (userList.isEmpty()) {
            log.info("数据库无用户，跳过。");
            return;
        }

        // 3. 重新构建 Redis ZSET
        // 使用 Pipeline 管道技术批量写入，速度比 for 循环单条写入快几十倍
        // 即使是 10万 条数据，也就几百毫秒的事
        // 这里为了代码简单，先用 for 循环，25条数据也就 1ms
        int count = 0;
        for (User user : userList) {
            // 只处理 rating 不为空的用户
            // 如果你的系统允许 rating 为 null，记得判空给默认值 1500
            Integer rating = user.getRating() == null ? 0 : user.getRating();

            // 写入 Redis: ZADD key score member
            redisTemplate.opsForZSet().add(RANK_KEY, String.valueOf(user.getId()), rating);
            count++;
        }

        log.info("排行榜预热完成！共同步 {} 位用户。", count);
        log.info("-------------------------------------");
    }
}
