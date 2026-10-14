package top.everblue.oj.contest.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import top.everblue.oj.contest.entity.ContestRank;

import java.util.List;

/**
 * 最终成绩表 Mapper。
 * <p>
 * 两个定制方法都服务于 {@link top.everblue.oj.contest.service.impl.ContestRankServiceImpl#finalizeContest}：
 * 「先删后插」是那个方法幂等性的来源，因此必须成对提供。
 * </p>
 */
@Mapper
public interface ContestRankMapper extends BaseMapper<ContestRank> {

    /**
     * 删除某场比赛的全部成绩记录。
     * <p>
     * 用<b>物理删除</b>而非逻辑删除：这里处理的是「重新固化同一场比赛」，
     * 目的是让旧快照彻底消失，置 is_deleted 只会让同一批数据以两种状态共存，
     * 按 is_deleted=0 过滤时看着没问题，一旦有查询漏掉该条件就会出现重复行。
     * </p>
     *
     * @param contestId 比赛 ID
     * @return 删除的行数
     */
    int deleteByContestId(@Param("contestId") Long contestId);

    /**
     * 批量插入成绩记录。
     * <p>
     * 走一条 {@code INSERT ... VALUES (...),(...)...} 而非逐条插入：
     * 一场比赛动辄数百名选手，逐条插入的往返次数与事务日志写入量都不可接受。
     * </p>
     *
     * @param list 待插入的记录列表；为空时不应调用（调用方需先判空）
     * @return 插入的行数
     */
    int batchInsert(@Param("list") List<ContestRank> list);
}
