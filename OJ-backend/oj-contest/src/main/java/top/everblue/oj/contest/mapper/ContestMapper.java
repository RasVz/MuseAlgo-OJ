package top.everblue.oj.contest.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import top.everblue.oj.contest.entity.Contest;

/**
 * 比赛表 Mapper，仅提供一个通用框架无法表达的加锁查询。
 * <p>
 * 注意本接口<b>没有 {@code @Mapper} 注解</b>，与本模块其他 Mapper 不同——
 * 它依赖全局的 {@code @MapperScan} 扫描注册。改动注册方式时需保持这里的约定，
 * 否则 Spring 启动时会报「该接口没有对应的 Bean」。
 * </p>
 */
public interface ContestMapper extends BaseMapper<Contest> {

    /**
     * 悲观锁读取比赛行（{@code SELECT ... FOR UPDATE}）。
     * <p>
     * <b>必须在事务内调用</b>：锁在事务提交/回滚时才释放，
     * 在非事务上下文中执行与普通 select 无异，起不到串行化作用。
     * </p>
     * <p>
     * 显式带上 {@code is_deleted = 0}：锁住一条已逻辑删除的行并拿它当有效比赛使用，
     * 会让后续所有基于它的判定出错。
     * </p>
     *
     * @param id 比赛 ID
     * @return 比赛记录；不存在或已删除时返回 {@code null}
     */
    @Select("SELECT * FROM contest WHERE id = #{id} AND is_deleted = 0 FOR UPDATE")
    Contest selectByIdForUpdate(@Param("id") Long id);
}
