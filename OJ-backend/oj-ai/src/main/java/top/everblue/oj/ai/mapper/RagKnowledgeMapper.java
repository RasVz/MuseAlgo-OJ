package top.everblue.oj.ai.mapper;

import lombok.Data;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * RAG 第二版：多源检索（不需要 embedding 的过渡方案）。
 * <ul>
 *   <li>相关题目：共享标签共现排序 + 难度接近（结构化"语义近似"）</li>
 *   <li>课件：同一题单优先，其次标签名关键词命中</li>
 * </ul>
 */
@Mapper
public interface RagKnowledgeMapper {

    /** 当前题目难度：相关题排序要用（难度越接近越靠前） */
    @Select("SELECT difficulty FROM problem WHERE id = #{problemId} AND is_deleted = 0")
    Integer findDifficultyByProblemId(@Param("problemId") Long problemId);

    /** 当前题目的标签名（中文，如 背包DP），用于课件关键词检索 */
    @Select("""
            SELECT t.name FROM tag t
            JOIN problem_tag pt ON pt.tag_id = t.id
            WHERE pt.problem_id = #{problemId}
            ORDER BY t.id
            """)
    List<String> findTagNamesByProblemId(@Param("problemId") Long problemId);

    /**
     * 相关题目：和当前题共享的标签越多越相关；同标签下难度越接近越靠前。
     * 排序不靠模型，靠 SQL——这是"结构化检索"的核心优势。
     */
    @Select("""
            SELECT p.id, p.title, p.difficulty, p.tags, p.hint
            FROM problem p
            JOIN problem_tag pt ON pt.problem_id = p.id
            WHERE pt.tag_id IN (
                    SELECT tag_id FROM problem_tag WHERE problem_id = #{problemId}
            )
              AND p.id != #{problemId}
              AND p.is_deleted = 0
              AND p.is_public = 1
            GROUP BY p.id
            ORDER BY COUNT(*) DESC, ABS(p.difficulty - #{difficulty}) ASC
            LIMIT #{limit}
            """)
    List<RelatedProblemRow> findRelatedProblems(@Param("problemId") Long problemId,
                                                @Param("difficulty") int difficulty,
                                                @Param("limit") int limit);

    /**
     * 课件：同一题单（老师编排的强关联）优先，其次标签名关键词命中。
     */
    @Select("""
            <script>
            SELECT id, title, content FROM courseware
            WHERE is_deleted = 0 AND privacy = 1
              AND (
                problem_list_id IN (
                  SELECT list_id FROM problem_list_item WHERE problem_id = #{problemId}
                )
                <if test="keywords != null and keywords.size() > 0">
                OR
                <foreach collection="keywords" item="kw" separator=" OR ">
                  title LIKE CONCAT('%', #{kw}, '%') OR content LIKE CONCAT('%', #{kw}, '%')
                </foreach>
                </if>
              )
            ORDER BY id DESC
            LIMIT #{limit}
            </script>
            """)
    List<CoursewareRow> findCourseware(@Param("problemId") Long problemId,
                                       @Param("keywords") List<String> keywords,
                                       @Param("limit") int limit);

    @Data
    class RelatedProblemRow {
        private Long id;
        private String title;
        private Integer difficulty;
        private String tags;
        private String hint;
    }

    @Data
    class CoursewareRow {
        private Long id;
        private String title;
        private String content;
    }
}
