package top.everblue.oj.contest.service;

import com.baomidou.mybatisplus.spring.service.IService;
import top.everblue.oj.contest.entity.ContestUser;

/**
 * 比赛报名服务。
 * <p>
 * 职责很窄：只有「报名」这一件事。成绩、报名者列表的反向查询都放在别处，
 * 避免把 ContestUser 与 Contest 之间的关系实体当成业务聚合来用。
 * </p>
 */
public interface ContestUserService extends IService<ContestUser> {

    /**
     * 报名比赛的核心业务方法
     * @param contestId 比赛ID
     * @param userId    用户ID
     */
    void registerContest(Long contestId, Long userId);
}