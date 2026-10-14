package top.everblue.oj.contest.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.everblue.oj.contest.entity.Contest;
import top.everblue.oj.contest.entity.ContestUser;
import top.everblue.oj.contest.mapper.ContestMapper;
import top.everblue.oj.contest.mapper.ContestUserMapper;
import top.everblue.oj.contest.service.ContestUserService;

/**
 * 比赛报名服务实现。
 * <p>
 * 报名要同时写两处数据：插入 contest_user 关系行、更新 contest 表上的冗余报名人数。
 * 因此整个方法在同一事务内——只插关系行会让页面人数显示不准，
 * 只改人数则会让该用户实际无法参赛。
 * </p>
 */
@Service
@RequiredArgsConstructor
public class ContestUserServiceImpl extends ServiceImpl<ContestUserMapper, ContestUser> implements ContestUserService {

    // 注入比赛表的 Mapper，用来给报名人数 +1
    private final ContestMapper contestMapper;

    /**
     * 报名某场比赛。
     * <p>
     * <b>防重复报名的检查放在应用层而非只依赖数据库唯一约束</b>：
     * 需要给用户一条明确的提示文案，数据库冲突只能抛出含义不明的约束名异常。
     * 应用层检查不是并发安全的（两个并发请求可能同时通过 count），
     * 因此真正的保证仍然是数据库上的唯一索引——这里的检查负责友好提示，唯一索引负责正确性。
     * </p>
     *
     * @param contestId 比赛 ID
     * @param userId    用户 ID
     * @throws RuntimeException 该用户已报名过该比赛时抛出
     */
    @Override
    @Transactional(rollbackFor = Exception.class) // 开启事务，保证两步操作要么全成功，要么全失败
    public void registerContest(Long contestId, Long userId) {

        // 1. 检查是否已经报名过
Long count = this.count(new LambdaQueryWrapper<ContestUser>()
                .eq(ContestUser::getContestId, contestId)
                .eq(ContestUser::getUserId, userId));

        // 提前抛异常回滚，避免白跑一遍后续的两次写库
        if (count > 0) {
            throw new RuntimeException("您已经报名过该比赛啦！");
        }

        // 2. 生成一条报名记录并插入数据库
        ContestUser record = new ContestUser();
        record.setContestId(contestId);
        record.setUserId(userId);
        this.save(record);

        // 3. 更新比赛表，把该比赛的 participant_count 字段 + 1
        //    用 setSql 做「列 = 列 + 1」而非先查后写：后者在并发报名下会丢失更新
        //    （两次都读到同一个旧值，各自写回旧值+1，最终只涨了1）
        contestMapper.update(null, new LambdaUpdateWrapper<Contest>()
                .eq(Contest::getId, contestId)
                .setSql("participant_count = participant_count + 1"));
    }
}