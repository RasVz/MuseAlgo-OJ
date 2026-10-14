package top.everblue.oj.contest.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import top.everblue.oj.contest.entity.Contest;

/**
 * 比赛主数据服务。
 * <p>
 * 覆盖比赛自身的增删改查，并额外提供「按班级分页查询」与「加锁读取」两项非通用能力。
 * 榜单与报名不在此接口内，分别由 {@link ContestRankService} 与 {@link ContestUserService} 负责。
 * </p>
 */
public interface ContestService extends IService<Contest> {

    /**
     * 按班级分页查询该班级下的比赛列表，支持关键字过滤与指定字段排序。
     *
     * @param classId 班级 ID
     * @param currentPage 页码，从 1 开始
     * @param pageSize 每页条数
     * @param keyword 标题/描述关键字，为 {@code null} 或空串时不做过滤
     * @param sortField 排序字段名；必须是白名单内的列，非法值应回退到默认排序而非直接拼进 SQL
     * @param sortOrder 排序方向，{@code asc} 或 {@code desc}
     * @return 分页结果
     */
    Page<Contest> getClassContestList(Long classId, Integer currentPage, Integer pageSize, String keyword, String sortField, String sortOrder);

    /** 在当前事务中锁定比赛行，用于串行化同一比赛的会话创建。 */
    Contest getByIdForUpdate(Long contestId);

}
