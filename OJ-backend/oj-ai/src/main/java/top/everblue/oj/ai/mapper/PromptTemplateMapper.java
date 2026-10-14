package top.everblue.oj.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import top.everblue.oj.ai.entity.PromptTemplate;

import java.util.List;

/**
 * 提示词模板 Mapper，含一版一存的串行化控制。
 * <p>
 * 模板按 {@code promptKey + version} 存多版本而不是原地更新：线上调提示词需要能回滚到上一版，
 * 原地覆盖会让「上一版到底写了什么」永远查不到。
 * </p>
 * <p>
 * {@link #lockPromptKey(String)} 先往一张只含 {@code prompt_key} 唯一键的锁表插一行并靠
 * {@code ON DUPLICATE KEY UPDATE} 幂等兜底，效果是<strong>把并发写同一 key 的事务串行化</strong>。
 * 用一张空表当分布式锁，是因为提示词发布是两个动作（取最高版本号、插入新版本），
 * 必须原子化才能避免并发下产生两个相同版本号或版本号跳空。
 * </p>
 * <p>
 * {@link #selectByPromptKeyForUpdate(String)} 里的 {@code FOR UPDATE} 与上面的锁表配合使用：
 * 锁行拿不到才退到行锁，两个查询都必须在<strong>同一个事务</strong>内，否则锁会立即释放、形同虚设。
 * </p>
 * <p>
 * 注意这两条注解 SQL <strong>不带 {@code <script>}</strong>，按原始 SQL 处理，
 * 因此不要在里面写 {@code &lt;} {@code &gt;} 这类 XML 转义符——那会让语句直接语法错误。
 * 括号等普通 SQL 字符保持原样即可。
 * </p>
 */
@Mapper
public interface PromptTemplateMapper extends BaseMapper<PromptTemplate> {

    @Insert("INSERT INTO prompt_template_lock (prompt_key) VALUES (#{promptKey}) "
            + "ON DUPLICATE KEY UPDATE prompt_key = VALUES(prompt_key)")
    int lockPromptKey(@Param("promptKey") String promptKey);

    @Select("SELECT * FROM prompt_template WHERE prompt_key = #{promptKey} "
            + "ORDER BY version DESC FOR UPDATE")
    List<PromptTemplate> selectByPromptKeyForUpdate(@Param("promptKey") String promptKey);
}
