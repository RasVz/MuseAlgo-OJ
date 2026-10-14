package top.everblue.oj.contest.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import top.everblue.oj.contest.entity.VpAttempt;

/**
 * 虚拟赛会话 Mapper，无需定制方法。
 * <p>
 * VP 的查询都只是「按 contestId + userId + status 查一条」这类简单条件，
 * 用 {@code BaseMapper} 的条件构造器即可表达；成绩明细来自 submission 侧，
 * 由 oj-api 的只读契约接口提供，不在本Mapper 内。
 * </p>
 */
@Mapper
public interface VpAttemptMapper extends BaseMapper<VpAttempt> {
}
