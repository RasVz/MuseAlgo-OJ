package top.everblue.oj.community.favorite;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.everblue.oj.community.entity.UserFavorite;
import top.everblue.oj.community.interaction.CommunityInteractionService;
import top.everblue.oj.community.interaction.InteractionAction;
import top.everblue.oj.community.interaction.InteractionCommand;
import top.everblue.oj.community.interaction.InteractionTargetType;
import top.everblue.oj.community.service.UserFavoriteService;
import top.everblue.oj.api.problem.ProblemListCommand;
import top.everblue.oj.api.problem.ProblemListView;
import top.everblue.oj.api.problem.ProblemListQuery;
import top.everblue.oj.api.submission.PageResult;
import top.everblue.oj.community.entity.vo.UserFavoriteVO;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.stream.Collectors;

/**
 * 收藏的应用服务：把「收藏」这一动作按目标类型分派到各自的实现。
 * <p>
 * <b>为什么动态收藏要走互动服务而不是直接改 {@code user_favorite}：</b>
 * 动态的收藏同时牵动三处状态——收藏记录、{@code post.favorites} 计数、
 * Redis 热度排行，外加通知。这些副作用已由
 * {@link top.everblue.oj.community.interaction.PostInteractionHandler} 统一承担，
 * 本类若再自行改表，就会与它各写一份，计数必然漂移。
 * 所以这里只做转发，让动态与其他目标共用同一条互动链路。
 * </p>
 * <p>
 * <b>返回值的语义不对称，这里是刻意的：</b>
 * {@link #deactivate} 对动态以外的目标<b>永远返回 {@code false}</b>，
 * 它表达的是「新的状态」而非「是否发生变化」——
 * 取消收藏后状态自然为 false。调用方若把它当「是否改变」用会误判，
 * 请按「返回当前状态」理解。
 * </p>
 * <p>
 * 收藏目标是软取消状态机（{@code status} 0/1），取消不删行。
 * </p>
 */
@Service
@RequiredArgsConstructor
class FavoriteApplicationServiceImpl implements FavoriteApplicationService {

    private final UserFavoriteService userFavoriteService;
    private final ProblemListQuery problemListQuery;
    private final ProblemListCommand problemListCommand;
    private final JdbcTemplate jdbcTemplate;
    private final CommunityInteractionService interactionService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean activate(FavoriteCommand command) {
        command.validate();
        if (command.targetType() == FavoriteTargetType.POST) {
            return interactionService.activate(postCommand(command)).active();
        }
        requireGenericTarget(command);
        return changeGeneric(command, true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deactivate(FavoriteCommand command) {
        command.validate();
        if (command.targetType() == FavoriteTargetType.POST) {
            interactionService.deactivate(postCommand(command));
            return false;
        }
        requireGenericTarget(command);
        changeGeneric(command, false);
        return false;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean toggle(FavoriteCommand command) {
        command.validate();
        if (command.targetType() != FavoriteTargetType.POST) requireGenericTarget(command);
        return isActive(command) ? deactivate(command) : activate(command);
    }

    @Override
    public boolean isActive(FavoriteCommand command) {
        command.validate();
        if (command.targetType() == FavoriteTargetType.POST) {
            return interactionService.isActive(postCommand(command));
        }
        return userFavoriteService.count(favoriteQuery(command).eq(UserFavorite::getStatus, 1)) > 0;
    }

    /**
     * 批量查询一批目标的收藏状态。
     * <p>
     * 一次 {@code IN} 查询解决整页渲染，避免逐条调用 {@link #isActive} 造成 N+1。
     * </p>
     * <p>
     * <b>所有传入 ID 都出现在返回值中（未收藏的为 {@code false}）：</b>
     * 前端直接按下标取值即可，不必再做「查不到就是没收藏」的推断；
     * 返回缺键的 Map 会把这类判断散落到每个调用点。
     * 用户未登录时同样返回全 {@code false} 的完整 Map。
     * </p>
     *
     * @param targetIds 为 {@code null} 或空时返回空 Map
     */
    @Override
    public Map<Long, Boolean> activeStates(Long userId, FavoriteTargetType targetType, List<Long> targetIds) {
        Map<Long, Boolean> states = new LinkedHashMap<>();
        if (targetIds == null) return states;
        targetIds.forEach(id -> states.put(id, false));
        if (userId == null || targetIds.isEmpty()) return states;
        Set<Long> active = userFavoriteService.list(new LambdaQueryWrapper<UserFavorite>()
                        .eq(UserFavorite::getUserId, userId)
                        .eq(UserFavorite::getTargetType, targetType.databaseValue())
                        .in(UserFavorite::getTargetId, targetIds)
                        .eq(UserFavorite::getStatus, 1))
                .stream().map(UserFavorite::getTargetId).collect(Collectors.toSet());
        targetIds.forEach(id -> states.put(id, active.contains(id)));
        return states;
    }

    /**
     * 各类收藏的数量，供「我的收藏」入口的角标展示。
     * <p>
     * 只返回枚举中已定义的类型（题目、题单、动态），数据库里若存在其他
     * {@code target_type} 取值会被<b>静默跳过</b>——
     * 脏数据不该让接口报错，但也不该凭空多出一个前端不认识的角标。
     * </p>
     *
     * @return key 为各类型的响应字段名，值为数量（无收藏时为 0）；用户未登录时全为 0
     */
    @Override
    public Map<String, Integer> activeCounts(Long userId) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (FavoriteTargetType type : List.of(FavoriteTargetType.PROBLEM, FavoriteTargetType.PROBLEM_LIST, FavoriteTargetType.POST)) {
            counts.put(type.responseKey(), 0);
        }
        if (userId == null) return counts;
        jdbcTemplate.queryForList("SELECT target_type, COUNT(*) AS cnt FROM user_favorite WHERE user_id = ? AND status = 1 GROUP BY target_type", userId)
                .forEach(row -> {
                    try {
                        FavoriteTargetType type = FavoriteTargetType.from(row.get("target_type"));
                        if (counts.containsKey(type.responseKey())) counts.put(type.responseKey(), ((Number) row.get("cnt")).intValue());
                    } catch (IllegalArgumentException ignored) { }
                });
        return counts;
    }

    @Override
    public PageResult<ProblemListView> favoriteProblemLists(Long userId, Integer page, Integer size, String keyword) {
        List<Long> targetIds = userFavoriteService.list(new LambdaQueryWrapper<UserFavorite>()
                        .eq(UserFavorite::getUserId, userId)
                        .eq(UserFavorite::getTargetType, FavoriteTargetType.PROBLEM_LIST.databaseValue())
                        .eq(UserFavorite::getStatus, 1)
                        .orderByDesc(UserFavorite::getUpdateTime).orderByDesc(UserFavorite::getCreateTime))
                .stream().map(UserFavorite::getTargetId).toList();
        // 走 oj-api 只读契约分页查询题单（含关键字过滤）
        return problemListQuery.findPageByIds(targetIds, keyword, page, size);
    }

    @Override
    public List<Map<String, Object>> favoritePosts(Long userId, Integer postType) {
        return jdbcTemplate.queryForList("SELECT p.id, p.title, p.summary, p.type, p.likes, p.comment_count, p.create_time, u.nickname AS authorName "
                        + "FROM user_favorite uf JOIN post p ON p.id = uf.target_id AND p.is_deleted = 0 "
                        + "LEFT JOIN user u ON u.id = p.author_id WHERE uf.user_id = ? AND uf.target_type = ? AND uf.status = 1 "
                        + "AND p.type = ? ORDER BY uf.create_time DESC LIMIT 50",
                userId, FavoriteTargetType.POST.databaseValue(), postType);
    }

    /**
     * 题单收藏的聚合分页视图（跨模块拼装社区与题单两侧的数据）。
     * <p>
     * 手工拼 SQL 而不用 {@link UserFavoriteVO} + 实体查询，是因为需要
     * {@code user_favorite} JOIN {@code problem_list} JOIN {@code user} 三表，
     * 且分页总数与数据行要用<b>同一个</b> {@code FROM}/{@code WHERE}
     * （{@code base}）——写成两个独立查询时，计数与数据的过滤条件极易漂移不一致。
     * </p>
     * <p>
     * 页码与每页条数都做了兜底（页码至少 1、条数 1~100），
     * 防止前端传 {@code size=0} 或超大值造成全表扫描。
     * </p>
     * <p>
     * 与 {@link #favoriteProblemLists} 功能重叠：本方法额外返回创建人、
     * 收藏数等题单详情字段，故用 JdbcTemplate 直查；
     * 若要合并两者，应以保留信息更全的一方为准。
     * </p>
     */
    @Override
    public Map<String, Object> favoriteProblemListView(Long userId, Long page, Long size, String keyword) {
        long safePage = page == null || page < 1 ? 1 : page;
        long safeSize = size == null || size < 1 ? 12 : Math.min(size, 100);
        long offset = (safePage - 1) * safeSize;
        String base = " FROM user_favorite uf INNER JOIN problem_list pl ON uf.target_id = pl.id LEFT JOIN `user` u ON pl.creator_id = u.id "
                + "WHERE uf.user_id = ? AND uf.target_type = ? AND uf.status = 1 ";
        List<Object> parameters = new ArrayList<>();
        parameters.add(userId); parameters.add(FavoriteTargetType.PROBLEM_LIST.databaseValue());
        if (keyword != null && !keyword.trim().isEmpty()) { base += "AND pl.title LIKE ? "; parameters.add("%" + keyword.trim() + "%"); }
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(1) " + base, Long.class, parameters.toArray());
        List<Object> dataParameters = new ArrayList<>(parameters); dataParameters.add(offset); dataParameters.add(safeSize);
        List<UserFavoriteVO> records = jdbcTemplate.query("SELECT pl.id AS targetId, pl.creator_id AS creatorId, u.username AS creatorUsername, "
                        + "COALESCE(pl.problem_count, 0) AS problemCount, COALESCE(pl.favorite_count, 0) AS favoriteCount, pl.title, pl.update_time AS updateTime "
                        + base + "ORDER BY uf.create_time DESC LIMIT ?, ?", dataParameters.toArray(), (rs, ignored) -> {
                    UserFavoriteVO view = new UserFavoriteVO();
                    view.setTargetId(rs.getLong("targetId")); view.setCreatorId(rs.getLong("creatorId"));
                    view.setCreatorUsername(rs.getString("creatorUsername")); view.setProblemCount(rs.getInt("problemCount"));
                    view.setFavoriteCount(rs.getInt("favoriteCount")); view.setTitle(rs.getString("title"));
                    Timestamp updatedAt = rs.getTimestamp("updateTime"); view.setUpdateTime(updatedAt == null ? null : updatedAt.toLocalDateTime());
                    return view;
                });
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records); result.put("total", total == null ? 0L : total); result.put("current", safePage); result.put("size", safeSize);
        return result;
    }

    /**
     * 非动态目标的收藏状态变更：不存在则插入，已存在则改 {@code status}。
     * <p>
     * 软取消语义：取消收藏只把 {@code status} 置 0 并记 {@code cancelTime}，
     * <b>不删行</b>，以保留首次收藏时间与「曾收藏」记录。
     * </p>
     * <p>
     * 状态未变化时提前返回（{@code wasActive == active}），
     * 避免无意义的 UPDATE 与计数抖动——计数只在状态真正翻转时才调整。
     * </p>
     *
     * @return 变更后的状态（取消收藏即返回 {@code false}，不是「是否改变」）
     * @throws IllegalStateException 插入失败
     */
    private boolean changeGeneric(FavoriteCommand command, boolean active) {
        UserFavorite favorite = userFavoriteService.getOne(favoriteQuery(command));
        if (favorite == null) {
            if (!active) return false;
            LocalDateTime now = LocalDateTime.now();
            favorite = new UserFavorite();
            favorite.setUserId(command.userId());
            favorite.setTargetId(command.targetId());
            favorite.setTargetType(command.targetType().databaseValue());
            favorite.setStatus(1);
            favorite.setCreateTime(now);
            favorite.setUpdateTime(now);
            if (!userFavoriteService.save(favorite)) throw new IllegalStateException("收藏操作失败");
            updateTargetCount(command.targetType(), command.targetId(), 1);
            return true;
        }
        boolean wasActive = Integer.valueOf(1).equals(favorite.getStatus());
        if (wasActive == active) return active;
        LocalDateTime now = LocalDateTime.now();
        userFavoriteService.update(new LambdaUpdateWrapper<UserFavorite>()
                .set(UserFavorite::getStatus, active ? 1 : 0)
                .set(UserFavorite::getCancelTime, active ? null : now)
                .set(UserFavorite::getUpdateTime, now)
                .eq(UserFavorite::getId, favorite.getId()));
        updateTargetCount(command.targetType(), command.targetId(), active ? 1 : -1);
        return active;
    }

    private void requireGenericTarget(FavoriteCommand command) {
        String table = switch (command.targetType()) {
            case PROBLEM -> "problem";
            case PROBLEM_LIST -> "problem_list";
            case CONTEST -> "contest";
            case POST -> throw new IllegalStateException("帖子收藏由互动模块处理");
        };
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE id = ?", Integer.class, command.targetId());
        if (count == null || count == 0) throw new IllegalArgumentException("收藏目标不存在");
    }

    private LambdaQueryWrapper<UserFavorite> favoriteQuery(FavoriteCommand command) {
        return new LambdaQueryWrapper<UserFavorite>().eq(UserFavorite::getUserId, command.userId())
                .eq(UserFavorite::getTargetId, command.targetId())
                .eq(UserFavorite::getTargetType, command.targetType().databaseValue());
    }

    private InteractionCommand postCommand(FavoriteCommand command) {
        return new InteractionCommand(command.userId(), InteractionTargetType.POST, command.targetId(), InteractionAction.FAVORITE);
    }

    private void updateTargetCount(FavoriteTargetType type, Long targetId, int delta) {
        if (type != FavoriteTargetType.PROBLEM_LIST) return;
        // 走 oj-api 写契约（下限保护由数据归属方控制）
        problemListCommand.adjustFavoriteCount(targetId, delta);
    }
}
