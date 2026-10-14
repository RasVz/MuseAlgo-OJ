package top.everblue.oj.problem.mapper;

import top.everblue.oj.problem.entity.ProblemCase;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * <p>
 * 判题用例表 Mapper 接口
 * </p>
 * <p>
 * 本表只存测试点的<b>元信息</b>（所属题目、分组、字节数、是否样例），
 * 输入输出正文不走这里，而是落在文件存储中，经
 * {@link top.everblue.oj.problem.service.TestCaseStorageService} 按 ID 读取。
 * 因此本接口<b>没有自定义查询方法</b>——按 ID 取正文必须走存储服务，
 * 直接查表只会取到路径字段而没有正文。
 * </p>
 * <p>
 * 同名 {@code resources/mapper/ProblemCaseMapper.xml} 目前只声明了
 * {@code BaseResultMap} 与 {@code Base_Column_List}，<b>不含任何语句</b>。
 * 在这里新增带 {@code @Select} 的方法前请注意：同名 mapper id 一旦同时存在
 * XML 定义与注解定义，MyBatis 会取 XML、<b>让注解静默失效并打出 ERROR</b>。
 * </p>
 *
 * @author RasVz
 * @since 2025-12-12
 */
public interface ProblemCaseMapper extends BaseMapper<ProblemCase> {

}
