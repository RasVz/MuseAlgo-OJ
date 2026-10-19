package top.everblue.oj;

import org.flywaydb.core.Flyway;

/** 发行包独立迁移入口：仅运行 Flyway，不启动 HTTP、判题 Worker 或定时任务。 */
public final class FlywayMigrationRunner {
    private FlywayMigrationRunner() {}
    public static void main(String[] args) {
        String host=System.getenv().getOrDefault("OJ_DB_HOST","mysql");
        String port=System.getenv().getOrDefault("OJ_DB_PORT","3306");
        String url="jdbc:mysql://"+host+":"+port+"/oj_db?useUnicode=true&characterEncoding=UTF-8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
        Flyway.configure().dataSource(url,"musealgo_migrator",required("OJ_DB_MIGRATION_PASS"))
                .locations("classpath:db/migration").baselineOnMigrate(true).baselineVersion("6").load().migrate();
    }
    private static String required(String name) {
        String value=System.getenv(name);
        if(value==null || value.isBlank())throw new IllegalArgumentException("Missing environment variable: "+name);
        return value;
    }
}
