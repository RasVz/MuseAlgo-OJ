package com.musealgo.oj.ai;
import com.musealgo.oj.ai.mapper.OfficialSolutionMapper;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** 单独注入的一次性 MySQL 库，只建合成 post 表；不启动应用或调用供应商。 */
@EnabledIfEnvironmentVariable(named="OJ_DIAGNOSTIC_SQL_URL",matches=".*")
class OfficialTutorReferenceSqlTest {
    @Test void realMapperExcludesPrivateDeletedUnrelatedAndClassScopedPosts() throws Exception {
        String url=System.getenv("OJ_DIAGNOSTIC_SQL_URL");
        if(!url.contains("/diag_audit_"))throw new IllegalArgumentException("必须使用 diag_audit_ 一次性库");
        var data=new UnpooledDataSource("com.mysql.cj.jdbc.Driver",url,"diag_audit",System.getenv("OJ_DIAGNOSTIC_SQL_PASSWORD"));
        try(var connection=data.getConnection();var sql=connection.createStatement()) {
            sql.execute("CREATE TABLE post(id BIGINT PRIMARY KEY,problem_id BIGINT,title VARCHAR(100),content TEXT,type INT,is_official INT,is_deleted INT,privacy INT,class_id BIGINT,is_essence INT,likes INT)");
            sql.execute("INSERT INTO post VALUES (10,1,'公开官方','完整公开题解',1,1,0,0,NULL,1,5),(11,1,'公开备选','另一公开题解',1,1,0,NULL,NULL,0,1),(20,1,'私有','PRIVATE_SECRET',1,1,0,1,NULL,1,999),(21,1,'已删除','DELETED',1,1,1,0,NULL,1,999),(22,2,'跨题','OTHER_PROBLEM',1,1,0,0,NULL,1,999),(23,1,'班级','CLASS_PRIVATE',1,1,0,0,7,1,999),(24,1,'非官方','USER_ANSWER',1,0,0,0,NULL,1,999)");
        }
        var config=new Configuration(new Environment("synthetic",new JdbcTransactionFactory(),data));config.addMapper(OfficialSolutionMapper.class);
        try(var session=new SqlSessionFactoryBuilder().build(config).openSession()) {
            var mapper=session.getMapper(OfficialSolutionMapper.class);
            assertEquals(10L,mapper.findPreferred(1L,null).id());assertEquals("完整公开题解",mapper.findPreferred(1L,10L).content());
            for(long id:new long[]{20,21,22,23,24})assertNull(mapper.findPreferred(1L,id));
            assertEquals(2,mapper.listOfficial(1L).size());assertEquals("完整公开题解",mapper.findOfficialSolutionByProblemId(1L));
        }
        System.out.println("REAL_MYSQL_REFERENCE: public default/selected record mapping passed; private/deleted/cross-problem/class/nonofficial excluded");
    }
}
