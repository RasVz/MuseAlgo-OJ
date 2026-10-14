package top.everblue.oj.contest.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import top.everblue.oj.contest.entity.ContestUser;

/**
 * 比赛报名关系 Mapper，无需定制方法。
 * <p>
 * 报名表只有「增、查是否存在、列出报名者」三类操作，
 * 全部可由 MyBatis-Plus 的 {@code BaseMapper} 覆盖，故不写任何 SQL。
 * </p>
 */
@Mapper
public interface ContestUserMapper extends BaseMapper<ContestUser> {
    // 基础的增删改查 MyBatis-Plus 已经包揽了，这里留空即可
}