package top.everblue.oj.community.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import top.everblue.oj.community.entity.CommentLike;

/**
 * 评论点赞记录 Mapper，全部操作走 MyBatis-Plus 内置 CRUD，无自定义 SQL。
 */
@Mapper
public interface CommentLikeMapper extends BaseMapper<CommentLike> {
    // MyBatis-Plus 已经内置了基本的 CRUD，不需要额外写方法
    // 如果将来需要"根据 commentId 删除所有点赞"，可以直接用 queryWrapper 删除
}