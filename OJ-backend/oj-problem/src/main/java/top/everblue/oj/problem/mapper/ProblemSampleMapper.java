package top.everblue.oj.problem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import top.everblue.oj.problem.entity.ProblemSample;

/**
 * 题目公开样例 Mapper，无手写 SQL。
 * <p>
 * 样例与私有测试数据的读取方式刻意不同：样例是纯文本行，直接查表即可；
 * 测试数据走文件存储，需经存储服务读取，样例不要套用那套逻辑。
 * </p>
 */
@Mapper
public interface ProblemSampleMapper extends BaseMapper<ProblemSample> {
}
