package top.everblue.oj.common.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件装配，目前只挂分页插件。
 * <p>
 * {@link PaginationInnerInterceptor} 是 MyBatis-Plus 3.5.9 起推荐的写法：插件通过
 * {@link MybatisPlusInterceptor} 统一注册，内部插件按添加顺序依次织入 SQL 改写流程。
 * 以后要加多租户、乐观锁、防全表更新等插件，都应add 到同一个 interceptor 里，
 * 而不是再声明一个新的 {@code MybatisPlusInterceptor} Bean（多次声明会互相覆盖，只生效一个）。
 * </p>
 * <p>
 * 显式指定 {@link DbType#MYSQL} 是必须的：留空时插件运行期探测数据库类型，多一次查询且
 * 在部分代理/连接池场景下会探测失败。分页超出 maxLimit 时插件会截断而非报错，
 * 这个行为对调用方不可见，所以调用分页接口不要把页码当无限下拉来用。
 * </p>
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * 新的分页插件,一缓和二缓遵循mybatis的规则
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 指定数据库类型为 MySQL
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}