package com.musealgo.oj.contest.entity;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProblemContestMappingTest {

    @Test
    void generatedSelectOnlyReadsColumnsInTheContestProblemRelation() {
        TableInfo info = TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "problem-contest-mapping-test"),
                ProblemContest.class);
        Set<String> columns = Arrays.stream(info.getAllSqlSelect().split(","))
                .map(String::trim).collect(Collectors.toSet());

        assertTrue(columns.containsAll(Set.of("id", "contest_id", "problem_id", "problem_order",
                "score", "visible", "create_time", "update_time", "is_deleted")));
        assertFalse(columns.contains("problem_title"));
        assertFalse(columns.contains("contest_title"));
        assertFalse(columns.contains("difficulty"));
    }
}
