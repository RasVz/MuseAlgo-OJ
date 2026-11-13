package com.musealgo.oj.controller;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.musealgo.oj.dto.AdminClassSummary;
import com.musealgo.oj.mapper.AdminClassDirectoryMapper;
import com.musealgo.oj.service.AdminClassDirectoryService;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Executes the mapper against a dedicated, disposable MySQL schema. The strict
 * URL guard prevents this test from running against the application's oj_db.
 */
class AdminClassDirectoryMapperIT {

    private static final String JDBC_URL_PROPERTY = "musealgo.admin.classes.jdbc-url";
    private static final String JDBC_USER_PROPERTY = "musealgo.admin.classes.jdbc-user";
    private static final String JDBC_PASSWORD_PROPERTY = "musealgo.admin.classes.jdbc-password";

    private static DataSource dataSource;
    private static SqlSessionFactory sessionFactory;
    private AdminClassDirectoryService service;
    private SqlSession session;

    @BeforeAll
    static void connectToDedicatedSchemaAndCreateMapper() throws Exception {
        String jdbcUrl = System.getProperty(JDBC_URL_PROPERTY);
        Assumptions.assumeTrue(jdbcUrl != null,
                "Set musealgo.admin.classes.jdbc-url to run against a disposable test schema");
        if (!jdbcUrl.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/musealgo_admin_classes_test_[a-z0-9_]+\\?.*")) {
            throw new IllegalStateException("Admin class mapper IT requires a dedicated musealgo_admin_classes_test_* schema");
        }
        dataSource = new DriverManagerDataSource(jdbcUrl,
                requiredProperty(JDBC_USER_PROPERTY), requiredProperty(JDBC_PASSWORD_PROPERTY));

        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setEnvironment(new Environment("admin-class-directory-it",
                new JdbcTransactionFactory(), dataSource));
        MybatisPlusInterceptor pagination = new MybatisPlusInterceptor();
        pagination.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        configuration.addInterceptor(pagination);
        configuration.addMapper(AdminClassDirectoryMapper.class);
        try (InputStream xml = Resources.getResourceAsStream("mapper/AdminClassDirectoryMapper.xml")) {
            new XMLMapperBuilder(xml, configuration,
                    "mapper/AdminClassDirectoryMapper.xml", configuration.getSqlFragments()).parse();
        }
        sessionFactory = new org.apache.ibatis.session.SqlSessionFactoryBuilder().build(configuration);
    }

    @BeforeEach
    void createSyntheticRows() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS class_student");
            statement.execute("DROP TABLE IF EXISTS `class`");
            statement.execute("DROP TABLE IF EXISTS `user`");
            statement.execute("CREATE TABLE `user` (id BIGINT PRIMARY KEY, username VARCHAR(100), nickname VARCHAR(100), is_deleted TINYINT NOT NULL DEFAULT 0)");
            statement.execute("CREATE TABLE `class` (id BIGINT PRIMARY KEY, name VARCHAR(120), teacher_id BIGINT, school VARCHAR(120), description VARCHAR(255), create_time DATETIME, is_deleted TINYINT NOT NULL DEFAULT 0)");
            statement.execute("CREATE TABLE class_student (class_id BIGINT NOT NULL, student_id BIGINT NOT NULL, INDEX idx_class_student_class_id (class_id))");
            statement.execute("INSERT INTO `user`(id,username,nickname,is_deleted) VALUES "
                    + "(1,'marker_teacher','   ',0),(2,'marker_deleted_teacher','Deleted Name',1)");
            statement.execute("INSERT INTO `class`(id,name,teacher_id,school,description,create_time,is_deleted) VALUES "
                    + "(11,'marker-Alpha',1,'School A','Alpha intro','2026-01-01 10:00:00',0),"
                    + "(12,'marker-Beta',1,'School A','Beta intro','2026-01-01 10:00:00',0),"
                    + "(13,'marker-Deleted',1,'School A','Hidden','2026-01-01 10:00:00',1),"
                    + "(14,'Hidden Name',2,'School B','Deleted teacher','2026-01-02 10:00:00',0)");
            statement.execute("INSERT INTO class_student(class_id,student_id) VALUES (11,101),(11,102)");
        }
        session = sessionFactory.openSession(true);
        service = new AdminClassDirectoryService(session.getMapper(AdminClassDirectoryMapper.class));
    }

    @AfterEach
    void closeSession() {
        if (session != null) {
            session.close();
        }
    }

    @AfterAll
    static void removeSyntheticTables() throws Exception {
        if (dataSource == null) {
            return;
        }
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS class_student");
            statement.execute("DROP TABLE IF EXISTS `class`");
            statement.execute("DROP TABLE IF EXISTS `user`");
        }
    }

    @Test
    void searchesFiltersCountsAndOrdersWithRealMysqlSql() {
        var all = service.list(1, 20, "marker", "all", "newest");
        assertEquals(List.of(12L, 11L), all.getRecords().stream().map(AdminClassSummary::getId).toList());
        assertEquals(0L, all.getRecords().get(0).getStudentCount());
        assertEquals(2L, all.getRecords().get(1).getStudentCount());
        assertEquals("marker_teacher", all.getRecords().get(0).getTeacherName());

        var empty = service.list(1, 20, "marker", "empty", "newest");
        assertEquals(List.of(12L), empty.getRecords().stream().map(AdminClassSummary::getId).toList());
        var hasMembers = service.list(1, 20, "marker", "hasMembers", "newest");
        assertEquals(List.of(11L), hasMembers.getRecords().stream().map(AdminClassSummary::getId).toList());

        var oldest = service.list(1, 20, "marker", "all", "oldest");
        assertEquals(List.of(11L, 12L), oldest.getRecords().stream().map(AdminClassSummary::getId).toList());
        var byName = service.list(1, 20, "marker", "all", "name");
        assertEquals(List.of(11L, 12L), byName.getRecords().stream().map(AdminClassSummary::getId).toList());

        var activeTeacher = service.list(1, 20, "marker_teacher", "all", "newest");
        assertEquals(List.of(12L, 11L), activeTeacher.getRecords().stream().map(AdminClassSummary::getId).toList());
        assertEquals(0L, service.list(1, 20, "marker_deleted_teacher", "all", "newest").getTotal());

        var deletedTeacherClass = service.list(1, 20, "14", "all", "newest").getRecords().get(0);
        assertNull(deletedTeacherClass.getTeacherName());
        assertEquals("2026-01-02T10:00", deletedTeacherClass.getCreateTime().toString());
        assertEquals(List.of(14L), service.list(1, 20, "School B", "all", "newest")
                .getRecords().stream().map(AdminClassSummary::getId).toList());

        var firstPage = service.list(1, 1, "marker", "all", "name");
        var secondPage = service.list(2, 1, "marker", "all", "name");
        assertEquals(2L, firstPage.getTotal());
        assertEquals(11L, firstPage.getRecords().get(0).getId());
        assertEquals(12L, secondPage.getRecords().get(0).getId());

        assertEquals(0L, service.list(1, 20, "%' OR 1=1 --", "all", "newest").getTotal());
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing dedicated MySQL test property: " + name);
        }
        return value;
    }
}
