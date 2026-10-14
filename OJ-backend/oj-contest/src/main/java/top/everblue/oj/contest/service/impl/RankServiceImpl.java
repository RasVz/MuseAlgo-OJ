package top.everblue.oj.contest.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import top.everblue.oj.api.user.UserDisplayInfo;
import top.everblue.oj.api.user.UserQuery;
import top.everblue.oj.contest.service.RankService;
import top.everblue.oj.contest.entity.vo.RankVO; // 假设你有个 VO 给前端

import java.util.*;
import java.util.stream.Collectors;

/**
 * 平台总排行榜（按累计评分）服务实现。
 * <p>
 * 与 {@link top.everblue.oj.contest.service.RankRedisService} 是<b>两套互不相干</b>的榜单：
 * 后者管单场比赛的实时榜，key 以 {@code contest:rank:} 开头、随比赛重置；
 * 本类管跨比赛的荣誉榜，key 固定为 {@code RANK_KEY}，选手数据长期累积。
 * 二者的名次规则也不同——本榜<b>按行号排名</b>（1,2,3），不做并列处理。
 * </p>
 */
@Service
@RequiredArgsConstructor
public class RankServiceImpl implements RankService {

    private final StringRedisTemplate redisTemplate; // 推荐用 StringRedisTemplate 处理简单的 K-V

    private final UserQuery userQuery;

    /**
     * 总榜 Redis key。固定单key，不按赛季或时间分片——
     * 与比赛榜不同，这里没有「一场比赛」这样的天然重置边界，加分页会破坏既有语义。
     */
    private static final String RANK_KEY = "oj:rank:rating";

    /**
     * 更新用户积分 (当题目AC后调用)
     * <p>
     * ZADD 语义天然幂等：同一 member 重复写入只会覆盖分数而不会新增成员。
     * </p>
     *
     * @param userId用户 ID
     * @param newRating 更新后的评分分
     */
    public void updateRank(Long userId, Integer newRating) {
        // ZADD oj:rank:rating <score> <userId>
        redisTemplate.opsForZSet().add(RANK_KEY, String.valueOf(userId), newRating);
    }

    /**
     * 获取前 N 名列表
     *
     * @param topN 获取多少人，比如 50
     * @return 按名次升序的榜单；无数据时返回空列表
     */
    public List<RankVO> getTopRank(int topN) {
        // 1. 从 Redis 取出 ID + 分数（B3：reverseRangeWithScores 一次取回，
        //    消除原先循环内对每个用户单独 ZSCORE 的 N 次往返）
        Set<ZSetOperations.TypedTuple<String>> topTuples =
                redisTemplate.opsForZSet().reverseRangeWithScores(RANK_KEY, 0, topN - 1);

        if (topTuples == null || topTuples.isEmpty()) {
            return new ArrayList<>();
        }

        // 2. 拿到 ID 后，去 MySQL 查用户详情 (头像、昵称)
        List<Long> idList = topTuples.stream()
                .map(tuple -> Long.valueOf(tuple.getValue()))
                .collect(Collectors.toList());
        Map<Long, UserDisplayInfo> userMap = userQuery.findDisplayInfoBatch(idList);

        // 3. 组装最终有序的 VO List（分数直接取 tuple，不再逐人 ZSCORE）
        List<RankVO> rankList = new ArrayList<>();
        int currentRank = 1;

        for (ZSetOperations.TypedTuple<String> tuple : topTuples) {
            Long uid = Long.valueOf(tuple.getValue());
            UserDisplayInfo user = userMap.get(uid);

            if (user != null) {
                RankVO vo = new RankVO();
                vo.setRank(currentRank++); // 设置名次：本榜按行号排名，同分不并列（与比赛榜规则不同）
                vo.setId(user.id());
                vo.setUsername(user.username());
                vo.setAvatar(user.avatar());
                vo.setAcceptedCount(user.acceptedCount());
                vo.setMotto(user.motto());
                vo.setSchool(user.school());
                Double score = tuple.getScore();
                // 分数为 null 说明 ZSet 中该成员的 score 异常，宁可显示 0 也不要抛 NPE 中断整榜
                vo.setRating(score != null ? score.intValue() : 0);

                rankList.add(vo);
            }
        }

        return rankList;
    }

    /**
     * 获取我的排名
     * <p>
     * 用 {@code reverseRank} 由 Redis 直接算出位置（O(log N)），
     * 不必取回全榜在内存里数。
     * </p>
     *
     * @param userId 用户 ID
     * @return 从 1 开始的名次；用户不在榜上时返回 {@code -1}
     */
    public Long getMyRank(Long userId) {
        // 获取排名 (0开始)
        Long rank = redisTemplate.opsForZSet().reverseRank(RANK_KEY, String.valueOf(userId));
        // 用 -1 而非 null 表示「未上榜」：调用方多是需要直接把它塞进响应体，
        // null 在前端会渲染成空白数字，而 -1 明确表示「还没有排名」
        return rank == null ? -1 : rank + 1; // 转为 1开始
    }
}
