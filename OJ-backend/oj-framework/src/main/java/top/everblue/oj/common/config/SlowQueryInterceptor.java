package top.everblue.oj.common.config;

import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * MyBatis 慢查询拦截器（O8）。
 * <p>
 * 超过 {@value #SLOW_THRESHOLD_MS}ms 的 SQL 打 WARN 日志并附带 SQL，
 * 阈值可通过应用日志级别与告警链路再收敛（对接日志聚合时直接按关键字检索）。
 * 对耗时无影响：仅统计 + 打日志，不拦截执行。
 * </p>
 */
@Component
@Intercepts({
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class,
                        CacheKey.class, BoundSql.class}),
        @Signature(type = Executor.class, method = "update",
                args = {MappedStatement.class, Object.class})
})
public class SlowQueryInterceptor implements Interceptor {

    private static final Logger log = LoggerFactory.getLogger(SlowQueryInterceptor.class);

    private static final long SLOW_THRESHOLD_MS = 1000;

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        long start = System.currentTimeMillis();
        try {
            return invocation.proceed();
        } finally {
            long cost = System.currentTimeMillis() - start;
            if (cost >= SLOW_THRESHOLD_MS) {
                Object[] args = invocation.getArgs();
                String sql = "";
                if (args.length > 1 && args[0] instanceof MappedStatement ms) {
                    Object parameter = args[1];
                    try {
                        BoundSql boundSql = ms.getBoundSql(parameter);
                        sql = boundSql.getSql();
                    } catch (Exception ignored) {
                        // SQL 不可得时仍记录耗时
                    }
                }
                log.warn("【慢查询】{}ms >= {}ms, sql={}",
                        cost, SLOW_THRESHOLD_MS, sql.replaceAll("\\s+", " "));
            }
        }
    }

    @Override
    public Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }
}
