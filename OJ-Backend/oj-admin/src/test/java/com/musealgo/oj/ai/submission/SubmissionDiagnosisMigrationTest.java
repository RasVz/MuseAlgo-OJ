package com.musealgo.oj.ai.submission;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 在一次性 MySQL 上验证 V78 建新表并删除旧复盘表；不连接任何现有开发库。 */
@EnabledIfEnvironmentVariable(named = "OJ_DIAGNOSIS_TEST_JDBC",
        matches = "jdbc:mysql://127[.]0[.]0[.]1:13328/diagnosis_test.*")
@SpringBootTest(classes = SubmissionDiagnosisMigrationTest.Fixture.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SubmissionDiagnosisMigrationTest {
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    DataSource dataSource;

    @Test
    void migrationCreatesDiagnosisTableAndDropsLegacyReviewTable() {
        jdbc.execute("DROP TABLE IF EXISTS submission_ai_diagnosis");
        jdbc.execute("DROP TABLE IF EXISTS ai_submission_review");
        jdbc.execute("DROP TABLE IF EXISTS submission");
        jdbc.execute("CREATE TABLE submission (id bigint NOT NULL AUTO_INCREMENT, PRIMARY KEY (id)) " +
                "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci");

        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V77__ai_submission_review.sql"),
                new ClassPathResource("db/migration/V78__submission_ai_diagnosis.sql")).execute(dataSource);

        assertEquals(1, tableCount("submission_ai_diagnosis"));
        assertEquals(0, tableCount("ai_submission_review"));
        jdbc.update("INSERT INTO submission (id) VALUES (1)");
        jdbc.update("INSERT INTO submission_ai_diagnosis (submission_id, user_id, status, content) " +
                "VALUES (1, 7, 'SUCCEEDED', 'test')");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM submission_ai_diagnosis", Integer.class));
    }

    private int tableCount(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables " +
                "WHERE table_schema = DATABASE() AND table_name = ?", Integer.class, table);
    }

    @Configuration(proxyBeanMethods = false)
    static class Fixture {
        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(System.getenv("OJ_DIAGNOSIS_TEST_JDBC"),
                    "root", "synthetic-diagnosis-only");
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }
    }
}
