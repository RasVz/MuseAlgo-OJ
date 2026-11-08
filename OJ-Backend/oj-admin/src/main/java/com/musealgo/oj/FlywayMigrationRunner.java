package com.musealgo.oj;

/** 保持Compose已有入口兼容；状态查询必须使用新DatabaseMaintenance类，旧镜像不允许误执行迁移。 */
public final class FlywayMigrationRunner {
    private FlywayMigrationRunner() { }
    public static void main(String[] args) throws Exception {
        DatabaseMaintenance.main(args);
    }
}
