package com.musealgo.oj.ai.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * RAG 第一版：官方题解"结构化检索"。
 * 题解就在数据库里（post 表，type=1 题解、is_official=1 官方），
 * 按 problem_id 精确命中，不需要向量库。语义检索是后续课程。
 */
@Mapper
public interface OfficialSolutionMapper {

    @Select("""
            SELECT content FROM post
            WHERE problem_id = #{problemId}
              AND type = 1
              AND is_official = 1
              AND is_deleted = 0
              AND (privacy IS NULL OR privacy = 0) AND class_id IS NULL
            ORDER BY is_essence DESC, likes DESC
            LIMIT 1
            """)
    String findOfficialSolutionByProblemId(@Param("problemId") Long problemId);
    record OfficialPost(Long id,String title,String content) { }
    @Select("SELECT id,title,content FROM post WHERE problem_id=#{problemId} AND type=1 AND is_official=1 AND is_deleted=0 "
            + "AND (privacy IS NULL OR privacy=0) AND class_id IS NULL AND (#{postId} IS NULL OR id=#{postId}) ORDER BY is_essence DESC,likes DESC,id DESC LIMIT 1")
    OfficialPost findPreferred(@Param("problemId") Long problemId,@Param("postId") Long postId);

    @Select("SELECT id,title FROM post WHERE problem_id=#{problemId} AND type=1 AND is_official=1 AND is_deleted=0 "
            + "AND (privacy IS NULL OR privacy=0) AND class_id IS NULL ORDER BY is_essence DESC,likes DESC,id DESC LIMIT 100")
    java.util.List<java.util.Map<String,Object>> listOfficial(@Param("problemId") Long problemId);
}
