package top.everblue.oj.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import top.everblue.oj.system.entity.SystemConfig;

/**
 * 系统配置项 Mapper，键值对形式的管理后台配置。
 * <p>
 * 只定义 {@link #upsert} 而不给自定义查询：读取一律走 {@code selectOne(config_key = ?)}，
 * 本接口的价值只在于<strong>把「存在则更新、不存在则插入」收进一条 SQL</strong>。
 * 用 {@code saveOrUpdate} 做不到这点——它要先查再写，并发配置同一项时会互相覆盖。
 * </p>
 * <p>
 * <strong>注意这条注解 SQL 不带 {@code <script>}</strong>，按原始 SQL 处理：
 * 不要在里面写 {@code &lt;} {@code &gt;} 这类 XML 转义符，写了会直接语法错误。
 * 若将来要改成带条件的动态 SQL，才需要包上 {@code <script>} 并按 XML 规则转义。
 * </p>
 * <p>
 * 本表存的是「运营可以改、无需重启」的配置（公告开关、维护模式等）；
 * 影响基础设施行为（数据库地址、判题机、Redis）的配置<strong>不放这里</strong>，
 * 必须走 application.yml + 环境变量，否则配置本身就成了绕过环境注入的暗门。
 * 敏感项（密钥）一律不入表。
 * </p>
 */
@Mapper
public interface SystemConfigMapper extends BaseMapper<SystemConfig> {

    /** upsert：存在则更新 */
    @Insert("INSERT INTO system_config (config_key, config_value) VALUES (#{key}, #{value}) " +
            "ON DUPLICATE KEY UPDATE config_value = VALUES(config_value)")
    int upsert(@Param("key") String key, @Param("value") String value);
}
