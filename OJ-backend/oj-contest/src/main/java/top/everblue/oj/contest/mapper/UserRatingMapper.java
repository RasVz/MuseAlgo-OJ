package top.everblue.oj.contest.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import top.everblue.oj.contest.entity.UserRating;

/**
 * 评分流水 Mapper，无需定制方法。
 * <p>
 * 评分流水只有「按用户查历史」与「插入」两类操作，
 * 前者可用带条件构造器的 {@code selectList} 表达（排序字段也简单，无需自定义 SQL），
 * 后者由 {@code BaseMapper} 提供，因此不写任何 SQL。
 * </p>
 */
public interface UserRatingMapper extends BaseMapper<UserRating> {
}
