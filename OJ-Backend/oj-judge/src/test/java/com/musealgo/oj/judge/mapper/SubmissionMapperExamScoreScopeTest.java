package com.musealgo.oj.judge.mapper;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SubmissionMapperExamScoreScopeTest {

    @Test
    void trainingPaperScoreQueryIncludesOnlyJudgedPracticeSubmissions() throws Exception {
        String sql = SubmissionMapper.class.getMethod("selectBestScoresByProblems",
                        Long.class, java.util.Collection.class,
                        java.time.LocalDateTime.class, java.time.LocalDateTime.class)
                .getAnnotation(Select.class).value()[0];

        assertTrue(sql.contains("contest_id IS NULL"));
        assertTrue(sql.contains("vp_attempt_id IS NULL"));
        assertTrue(sql.contains("status BETWEEN 1 AND 5"));
        assertTrue(sql.contains("create_time &gt;= #{from}"));
        assertTrue(sql.contains("create_time &lt;= #{to}"));
    }
}
