package top.everblue.oj.contest.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import top.everblue.oj.contest.entity.Contest;
import top.everblue.oj.contest.entity.UserRating;
import top.everblue.oj.contest.entity.vo.UserRatingVO;
import top.everblue.oj.contest.mapper.UserRatingMapper;
// import top.everblue.oj.contest.service.ContestService; // 假设你有这个
import top.everblue.oj.contest.service.ContestService;
import top.everblue.oj.contest.service.UserRatingService; // 记得创建对应的Service接口

import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 评分流水服务实现：读时把持久化记录拼装成图表用的 VO，写时批量落库。
 * <p>
 * 本类不参与评分计算，那是 {@link top.everblue.oj.contest.service.RatingCalculator} 的职责；
 * 这里只负责「已算好的结果」如何存取。
 * </p>
 */
@Service
@RequiredArgsConstructor
public class UserRatingServiceImpl implements UserRatingService {

    private final UserRatingMapper userRatingMapper;

    private final ContestService contestService; // 如果要查比赛名字需要这个

    /**
     * 查询评分历史并拼装成前端折线图直接可用的数据。
     * <p>
     * 按时间<b>升序</b>：图表是按 X 轴顺序连线的，顺序错了曲线会来回折返，
     * 而且首尾点会互换导致整条线看起来错乱。
     * </p>
     * <p>
     * 关联的比赛信息一次性批量取出（见方法内注释），不逐行查询。
     * </p>
     *
     * @param userId 选手 ID
     * @return 按时间升序的评分点列表；无记录时返回空列表
     */
    @Override
    public List<UserRatingVO> getRatingHistory(Long userId) {
        // 1. 查询该用户的所有 Rating 变动记录
        // 关键：一定要 orderByAsc(CreateTime)，否则图表的时间轴会乱
        List<UserRating> ratingList = userRatingMapper.selectList(
                new LambdaQueryWrapper<UserRating>()
                        .eq(UserRating::getUserId, userId)
                        .orderByAsc(UserRating::getCreateTime)
        );

        // 2. 批量预加载所有关联的 Contest（避免 N+1）
        // 这里必须先去重再批量查：一位选手可能参加过几十场比赛，
        // 若在下面的循环里逐行查 Contest，就是典型的 N+1 查询（1 次查流水 + N 次查比赛名），
        // 页面上多展示几场选手就会明显变慢。先收集 distinct 后的 ID 一次 listByIds 即可。
        List<Long> contestIds = ratingList.stream()
                .map(UserRating::getContestId).distinct().toList();
        Map<Long, Contest> contestMap = contestIds.isEmpty() ? Map.of() :
                contestService.listByIds(contestIds).stream()
                        .collect(Collectors.toMap(Contest::getId, c -> c));

        // 3. 转换为 VO
        return ratingList.stream().map(record -> {
            UserRatingVO vo = new UserRatingVO();
            // 用属性拷贝做字段搬运，但下面显式补的字段优先：
            // date 需要从 createTime 转换，contestName 来自关联查询，都不是简单同名映射
            BeanUtils.copyProperties(record, vo);
            vo.setDate(record.getCreateTime());
            vo.setContestId(record.getContestId());

            Contest contest = contestMap.get(record.getContestId());
            if (contest != null) {
                vo.setContestName(contest.getTitle());
            } else {
                // 比赛被删除时用「Rating赛 #<id>」占位，而不是留空：
                // 留空会让折线图的数据点没有悬浮标题，看起来像数据损坏；
                // 流水记录本身必须保留——它承载的是历史评分，不随比赛被删而失效
                vo.setContestName("Rating赛 #" + record.getContestId());
            }
            return vo;
        }).collect(Collectors.toList());
    }

    /**
     * 批量写入评分流水。
     * <p>
     * <b>没有真正使用批量 insert，而是循环单条 insert：</b>
     * 当前场次规模在数百条，单条插入的额外往返开销可以接受。
     * 若参赛规模上升到数千、需要优化，正确做法是引入 MyBatis-Plus 的
     * {@code saveBatch} 或自定义 foreach 批量语句，而不是去掉 {@code @Transactional}——
     * 去掉事务会换来「写入一半时异常导致评分曲线断层」的更严重问题。
     * </p>
     *
     * @param ratings 待写入的流水；为 {@code null} 或空集合时直接返回
     */
    @Override
    @Transactional
    public void batchSave(List<UserRating> ratings) {
        if (ratings == null || ratings.isEmpty()) return;
        for (UserRating r : ratings) {
            userRatingMapper.insert(r);
        }
    }
}
