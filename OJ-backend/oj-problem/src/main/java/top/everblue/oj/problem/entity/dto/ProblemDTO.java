package top.everblue.oj.problem.entity.dto;

import lombok.Data;
import top.everblue.oj.problem.entity.Problem;
import top.everblue.oj.problem.entity.ProblemSample;
import java.util.List;

// 这是一个组合类，专门用来接收前端发来的 "题目 + 公开样例"
/**
 * <p>
 * 题目新增/编辑的入参：继承 {@link Problem} 并额外携带公开样例。
 * <b>刻意继承实体而不是另立一组字段：</b>表单字段与表列一一对应，
 * 复制式映射能省掉一整套手写转换。但也意味着实体的所有字段都对外可见，
 * 因此<b>不可编辑字段</b>（{@code id} 之外的 ID 归属、统计计数、创建时间等）
 * 不能因为"DTO 能接住"就允许前端传入——落库时只挑可编辑字段赋值，
 * 见 {@code ProblemManagementApplicationService#applyEditableFields}。
 * </p>
 * <p>
 * 判题用例<b>不在</b>这个 DTO 里：测试数据体量大且需单独管理权限，
 * 走独立的用例接口。
 * </p>
 */
@Data
public class ProblemDTO extends Problem {
    // 题目对应的公开样例列表（判题用例由 ZIP 单独管理）
    private List<ProblemSample> samples;
}
