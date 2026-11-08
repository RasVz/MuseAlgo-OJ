package com.musealgo.oj;

import com.musealgo.oj.common.exception.BusinessException;
import com.musealgo.oj.system.error.SystemErrorCode;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.MigrationInfo;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** 独立维护入口：status只读，migrate先校验历史后升级；旧镜像没有此类时直接失败。 */
public final class DatabaseMaintenance {
    private DatabaseMaintenance() { }
    public static void main(String[] args) throws Exception {
        String operation = args.length == 0 ? "migrate" : args[0];
        if (!operation.equals("status") && !operation.equals("migrate"))
            throw new BusinessException(SystemErrorCode.SYSTEM_INPUT_INVALID, "Only status/migrate are supported");
        String host = System.getenv().getOrDefault("OJ_DB_HOST", "mysql");
        String port = System.getenv().getOrDefault("OJ_DB_PORT", "3306");
        String password = System.getenv("OJ_DB_MIGRATION_PASS");
        if (password == null || password.isBlank()) throw new BusinessException(SystemErrorCode.SYSTEM_INPUT_INVALID, "Missing OJ_DB_MIGRATION_PASS");
        Flyway flyway = Flyway.configure().dataSource("jdbc:mysql://"+host+":"+port
                        +"/oj_db?useUnicode=true&characterEncoding=UTF-8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true",
                        "musealgo_migrator", password)
                .locations("classpath:db/migration").baselineOnMigrate(false)
                // 待升级脚本是正常状态；历史缺失、校验变化和数据库超前均拒绝，禁止自动repair。
                .ignoreMigrationPatterns("*:pending").load();
        flyway.validate();
        MigrationInfo recordedCurrent = flyway.info().current();
        requireSupportedHistory(recordedCurrent == null ? null : recordedCurrent.getVersion());
        // 在任何DDL与停站前识别未知/冲突角色映射；只读检查，不因状态查询补写数据。
        try (var connection = flyway.getConfiguration().getDataSource().getConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT id,UPPER(code) FROM sys_role "
                     + "WHERE id IN (1,2,3) OR UPPER(code) IN ('STUDENT','TEACHER','ADMIN')")) {
            Map<Integer,String> roles = new LinkedHashMap<>();
            Map<String,Integer> roleCodeIds = new HashMap<>();
            while (rows.next()) {
                int id = rows.getInt(1);
                String code = rows.getString(2);
                if (code == null) throw new IllegalStateException("角色code为空；状态检查未修改数据，请先核对sys_role");
                Integer previousId = roleCodeIds.putIfAbsent(code, id);
                if (previousId != null && previousId != id)
                    throw new IllegalStateException("角色code重复；状态检查未修改数据，请先核对sys_role");
                roles.put(id, code);
            }
            validateReferenceRoleLayout(roles);
        }
        if (operation.equals("migrate")) flyway.migrate();
        var info = flyway.info();
        MigrationInfo current = info.current();
        String target = Arrays.stream(info.all()).map(MigrationInfo::getVersion)
                .filter(java.util.Objects::nonNull).max(java.util.Comparator.naturalOrder()).map(Object::toString).orElse("0");
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("current", current == null ? "0" : current.getVersion().toString());
        result.put("target", target);
        result.put("pending", Arrays.stream(info.pending()).map(m -> Map.of("version",m.getVersion().toString(),"description",m.getDescription())).toList());
        System.out.println("MUSEALGO_DATABASE_STATUS="+new ObjectMapper().writeValueAsString(result));
    }

    static void validateReferenceRoleLayout(Map<Integer,String> roles) {
        boolean legacy = "ADMIN".equals(roles.get(1)) && "STUDENT".equals(roles.get(3));
        if (legacy && roles.containsKey(2) && !"TEACHER".equals(roles.get(2)))
            throw new IllegalStateException("未知基础角色映射；状态检查未修改数据，请先核对sys_role");
        for (Map.Entry<Integer,String> role : roles.entrySet()) {
            int id = role.getKey();
            String code = role.getValue();
            if (id < 1 || id > 3) {
                if (code.equals("STUDENT") || code.equals("TEACHER") || code.equals("ADMIN"))
                    throw new IllegalStateException("基础角色code占用了自定义ID；状态检查未修改数据，请先核对sys_role");
                continue;
            }
            String expected = legacy
                    ? (id == 1 ? "ADMIN" : id == 2 ? "TEACHER" : "STUDENT")
                    : (id == 1 ? "STUDENT" : id == 2 ? "TEACHER" : "ADMIN");
            if (!expected.equals(code))
                throw new IllegalStateException("未知基础角色映射；状态检查未修改数据，请先核对sys_role");
        }
    }

    static void requireSupportedHistory(MigrationVersion current) {
        if (current == null || current.compareTo(MigrationVersion.fromVersion("6")) < 0)
            throw new IllegalStateException("Flyway历史缺失或早于V6基线；维护入口不会自动baseline空库，请先恢复受支持的V6以上历史");
    }
}
