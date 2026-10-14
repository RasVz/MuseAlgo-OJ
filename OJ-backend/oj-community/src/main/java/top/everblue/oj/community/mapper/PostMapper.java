package top.everblue.oj.community.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import top.everblue.oj.community.entity.Post;
import top.everblue.oj.community.entity.vo.PostVO;

import java.time.LocalDateTime;
import java.util.List;

/**
 * <p>
 * 文章/题解/博客表 Mapper 接口
 * </p>
 *
 * @author RasVz
 * @since 2025-12-12
 */
public interface PostMapper extends BaseMapper<Post> {

    // 列表查询 (支持所有筛选逻辑)
    IPage<PostVO> selectPostVOList(Page<?> page,
                                   @Param("category") String category,
                                   @Param("type") Integer type,
                                   @Param("types") List<Integer> types,
                                   @Param("authorId") Long authorId,  // 补上缺失的 authorId
                                   @Param("contestId") Long contestId,
                                   @Param("problemId") Long problemId,
                                   @Param("classId") Long classId,    // 调整了顺序，和 Service 保持一致
                                   @Param("keyword") String keyword,
                                   @Param("tags") List<String> tags,
                                   @Param("sort") String sort);

    IPage<PostVO> selectRelatedArticleList(Page<?> page, @Param("problemId") Long problemId,
                                            @Param("sort") String sort);

    // 详情查询
    PostVO selectPostVODetail(@Param("id") Long id);

    // 查询近期活跃的帖子（用于定时任务）。SQL 定义见 mapper/PostMapper.xml
    // 注意：注解与 XML 同时定义同一 id 时 XML 生效、注解被 MyBatis 忽略并打 ERROR 日志，故此处不再保留注解。
    List<Post> selectRecentActivePosts(@Param("since") LocalDateTime since,
                                       @Param("offset") int offset,
                                       @Param("limit") int limit);

    // 更新热度分（定时任务刷回）
    @Update("UPDATE post SET score = #{score}, hot_calculated_at = #{time} WHERE id = #{postId}")
    void updateHotScore(@Param("postId") Long postId,
                        @Param("score") double score,
                        @Param("time") LocalDateTime time);

    @Update("UPDATE post SET likes = #{likeCount} WHERE id = #{postId}")
    void updateLikeCount(@Param("postId") Long postId,
                         @Param("likeCount") Long likeCount);
}
