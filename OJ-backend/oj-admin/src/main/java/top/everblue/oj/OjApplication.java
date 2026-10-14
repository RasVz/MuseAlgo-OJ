package top.everblue.oj;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 应用启动类，也是唯一的组件扫描入口。
 * <p>
 * 本项目是多模块 Maven 工程：各业务模块（oj-system、oj-problem、oj-judge、oj-community、oj-contest、
 * oj-teaching、oj-analytics、oj-ai）作为依赖被 oj-admin 引入，启动类放在 oj-admin 的根包
 * {@code top.everblue.oj} 下，才能靠 {@code @SpringBootApplication} 的默认包扫描一次性覆盖到所有模块。
 * 换句话说，业务模块本身不声明启动类，也不该声明——多个启动类会各自建立独立容器。
 * </p>
 * <p>
 * {@link MapperScan} 逐个列出各模块的 mapper 包，原因与「按包扫描」相反：mapper 接口分散在
 * 不同模块的不同子包下，且 MyBatis-Plus 的 mapper 扫描范围应严格等于 mapper 所在包，
 * 混进其他包会误注册非 mapper 接口。新增业务模块时这里要同步补一行，漏了不会报错，
 * 只会在启动后调用时报「找不到 bean」。
 * </p>
 * <p>
 * {@link EnableScheduling} 开启全局定时任务，OJ 的热门榜同步、比赛封榜结算等调度任务依赖它；
 * 关闭它不会让应用启动失败，只会让定时任务静默不执行。
 * </p>
 */
@MapperScan({"top.everblue.oj.mapper",
             "top.everblue.oj.system.mapper",
             "top.everblue.oj.problem.mapper",
             "top.everblue.oj.judge.mapper",
             "top.everblue.oj.contest.mapper",
             "top.everblue.oj.community.mapper",
             "top.everblue.oj.teaching.mapper",
             "top.everblue.oj.analytics.mapper",
             "top.everblue.oj.ai.mapper"})
@SpringBootApplication
@EnableScheduling
public class OjApplication {
    public static void main(String[] args) {
        SpringApplication.run(OjApplication.class, args);
    }
}
