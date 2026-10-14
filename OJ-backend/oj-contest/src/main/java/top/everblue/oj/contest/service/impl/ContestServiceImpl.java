package top.everblue.oj.contest.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import top.everblue.oj.contest.entity.Contest;
import top.everblue.oj.contest.mapper.ContestMapper;
import top.everblue.oj.contest.service.ContestService;

import java.util.Map;

/**
 * 比赛主数据服务实现，基于 MyBatis-Plus 的 {@code ServiceImpl} 提供通用 CRUD。
 * <p>
 * 在通用能力之外只补了两处通用框架无法表达的东西：
 * 「按班级分页 + 安全的动态排序」与「加锁读取」。其余增删改查直接继承，不重复实现。
 * </p>
 */
@Service
public class ContestServiceImpl extends ServiceImpl<ContestMapper, Contest> implements ContestService {

    /**
     * 排序字段白名单：前端传入的驼峰字段名 → 实际数据库列名。
     * <p>
     * <b>必须走白名单而不是把 sortField 直接拼进 orderBy：</b>
     * 排序字段无法用 {@code #{}参数}绑定，只能拼接进 SQL 字符串，
     * 直接透传等于把 SQL 注入的口子开在前端参数上。
     * 查不到的字段名返回 {@code null} 从而回退到默认排序，非法输入被安全地忽略而非报错。
     * </p>
     */
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "id", "id",
            "title", "title",
            "startTime", "start_time",
            "endTime", "end_time",
            "createTime", "create_time"
    );

    /**
     * 按班级分页查询比赛列表。
     * <p>
     * <b>默认排序按比赛状态而非时间：</b>用 CASE 把当前时刻与赛程比较，
     * 排出「进行中 → 未开始 → 已结束」。这正是用户在班级页最关心的次序，
     * 单纯按开始时间排会把正在进行的比赛埋在列表中间。
     * 同组内再按 {@code start_time} 升序，使顺序稳定、可预期。
     * </p>
     * <p>
     * 该状态是<b>查询时用 SQL 现算</b>的，不落库、不缓存——比赛改期后无需任何数据同步即刻生效，
     * 也就不存在状态与时间不一致的脏数据。
     * </p>
     *
     * @param sortOrder 仅 {@code asc} 生效，其余值（含 {@code null}）一律按降序处理
     */
    @Override
    public Page<Contest> getClassContestList(Long classId, Integer currentPage, Integer pageSize, String keyword, String sortField, String sortOrder) {
        Page<Contest> page = new Page<>(currentPage, pageSize);
        QueryWrapper<Contest> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("class_id", classId);

        if (keyword != null && !keyword.trim().isEmpty()) {
            // 用 like 而非 eq：搜索框的语义是「包含」；
            // MyBatis-Plus 会把参数值参数化，此处不存在注入风险（注入风险在排序字段上，已由白名单化解）
            queryWrapper.like("title", keyword);
        }

        String column = sortField != null ? SORT_COLUMNS.get(sortField) : null;
        if (column != null) {
            if ("asc".equalsIgnoreCase(sortOrder)) {
                queryWrapper.orderByAsc(column);
            } else {
                // 默认降序：列表默认展示最新/最相关的在前，比升序更符合预期
                queryWrapper.orderByDesc(column);
            }
        } else {
            // 无排序参数时按「进行中 → 未开始 → 已结束」分组，组内按开始时间升序
            queryWrapper.orderByAsc("CASE " +
                            "WHEN NOW() >= start_time AND NOW() <= end_time THEN 1 " +
                            "WHEN NOW() < start_time THEN 2 " +
                            "ELSE 3 END",
                    "start_time");
        }

        return this.page(page, queryWrapper);
    }

    /**
     * {@inheritDoc}
     * <p>
     * 锁在事务结束才释放，因此调用方<b>必须在 {@code @Transactional} 方法内使用</b>，
     * 否则select 完成后行锁立即失效，退化成普通查询，起不到串行化作用。
     * </p>
     */
    @Override
    public Contest getByIdForUpdate(Long contestId) {
        return baseMapper.selectByIdForUpdate(contestId);
    }

}
