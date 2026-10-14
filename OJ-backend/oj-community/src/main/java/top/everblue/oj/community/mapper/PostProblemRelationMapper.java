package top.everblue.oj.community.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import top.everblue.oj.community.entity.PostProblemRelation;
import top.everblue.oj.community.entity.vo.RelatedProblemVO;

import java.util.List;

/**
 * 动态与题目的关联表 Mapper。
 * <p>
 * 关联行的写入是「全量覆盖」语义：编辑动态时先 {@link #deleteByPostId} 再整批插入，
 * 而不是逐条 diff——关联表数据量小（单条动态通常几个到几十个题目），
 * 全量覆盖的 SQL 次数固定为 2，避免了 diff 分支在并发编辑下的错漏。
 * </p>
 */
@Mapper
public interface PostProblemRelationMapper extends BaseMapper<PostProblemRelation> {
    /**
     * 按动态 ID 清空其全部题目关联。
     *
     * @return 删除行数；0 表示该动态原本就没有关联（编辑时属正常情况，不是错误）
     */
    @Delete("DELETE FROM post_problem_relation WHERE post_id = #{postId}")
    int deleteByPostId(@Param("postId") Long postId);

    /**
     * 查询某条动态关联的题目摘要。
     * <p>
     * 用 {@code JOIN} 直连 {@code problem} 表而非先查关联再逐个取题目：
     * 一次往返即可拿全，避免 N+1。
     * </p>
     * <p>
     * 题目侧用软删除，因此过滤条件写成 {@code is_deleted = 0 OR is_deleted IS NULL}——
     * 历史数据该列可能为 NULL，只判 {@code = 0} 会让这些题目凭空消失。
     * </p>
     *
     * @return 按关联行 id 升序的题目摘要列表，无关联时返回空集合而非 {@code null}
     */
    @Select("SELECT p.id, p.title, p.difficulty FROM post_problem_relation relation "
            + "JOIN problem p ON p.id = relation.problem_id "
            + "WHERE relation.post_id = #{postId} AND (p.is_deleted = 0 OR p.is_deleted IS NULL) ORDER BY relation.id ASC")
    List<RelatedProblemVO> selectProblemsByPostId(@Param("postId") Long postId);
}
