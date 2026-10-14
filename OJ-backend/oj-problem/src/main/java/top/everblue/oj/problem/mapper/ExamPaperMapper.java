package top.everblue.oj.problem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import top.everblue.oj.problem.entity.ExamPaper;

/**
 * 试卷 Mapper。
 * <p>
 * 只在 {@link BaseMapper} 之外追加了"加锁读取"这一个手写语句；
 * 其余读写一律走 MyBatis-Plus 的条件构造器，不在此处堆 SQL。
 * </p>
 */
@Mapper
public interface ExamPaperMapper extends BaseMapper<ExamPaper> {

    /**
     * 悲观锁读取单份未删除的试卷。
     * <p>
     * <b>MyBatis 转义铁律：本语句没有 {@code <script>} 包裹，走的是原始 SQL。</b>
     * 此处若出现 {@code <} / {@code >} 之类的比较符，<b>不能</b>写成 {@code &lt;} / {@code &gt;}——
     * 原始 SQL 不会做 XML 实体解码，转义符会被当成字面量送进数据库导致语法错误。
     * 只有 {@code <script>} 开头的注解才按 XML 解析、才需要转义。
     * 本语句之所以能安全使用，是因为它只用 {@code =} 作比较、不含尖括号。
     * </p>
     * <p>
     * {@code FOR UPDATE} 用于考试开考时的并发占位：避免两个请求同时判定"尚未开始"
     * 而都进入作答流程。需要配合事务使用，行锁在事务提交后才释放。
     * </p>
     *
     * @param id 试卷 ID
     * @return 加锁读到的试卷；试卷不存在或已软删除时返回 null
     */
    @Select("SELECT * FROM exam_paper WHERE id = #{id} AND is_deleted = 0 FOR UPDATE")
    ExamPaper selectByIdForUpdate(@Param("id") Long id);
}
