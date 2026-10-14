package top.everblue.oj.ai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.everblue.oj.ai.dto.HintLevelVo;
import top.everblue.oj.api.teaching.ClassQuery;
import top.everblue.oj.api.user.UserDisplayInfo;
import top.everblue.oj.api.user.UserQuery;
import top.everblue.oj.common.exception.ForbiddenException;
import top.everblue.oj.common.utils.SecurityUtils;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Hint Ladder 解锁情况查询（教师端学情）。
 * <p>
 * 班级学生经 {@link ClassQuery} 契约获取（oj-teaching 提供实现），
 * 用户名经 {@link UserQuery} 契约获取，解锁级别合并 Redis 当前值（{@link AiChatSessionService}）
 * 与轨迹表历史（{@link HintTraceQuery}）——全部走 oj-api 契约，不越模块边界。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HintLadderQueryService {

    private final ClassQuery classQuery;
    private final UserQuery userQuery;
    private final AiChatSessionService chatSessionService;

    /**
     * 查询班级内每位学生对某题的最高解锁级别。
     * <p>
     * 权限：班级教师本人或管理员（与班级学情 {@code ClassInsightService} 同策略）。
     * </p>
     *
     * @return 全班学生列表（含未解锁者，level 为 1），按解锁级别降序、再按 userId 升序
     */
    public List<HintLevelVo> classHintLevels(Long classId, Long problemId) {
        ClassQuery.ClassInfo classInfo = classQuery.findById(classId).orElse(null);
        if (classInfo == null) {
            throw new ForbiddenException("班级不存在");
        }
        Long currentUserId = SecurityUtils.getUserId();
        if (currentUserId == null
                || (!currentUserId.equals(classInfo.teacherId()) && !SecurityUtils.isAdmin())) {
            throw new ForbiddenException("无权限查看该班级的解锁情况");
        }

        List<Long> studentIds = classQuery.findStudentIds(classId);
        Map<Long, Integer> levels = chatSessionService.batchHintLevels(problemId, studentIds);
        Map<Long, UserDisplayInfo> users = userQuery.findDisplayInfoBatch(studentIds);

        return studentIds.stream()
                .map(userId -> new HintLevelVo(
                        userId,
                        users.get(userId) == null ? "用户" + userId : users.get(userId).username(),
                        levels.getOrDefault(userId, 1)))
                .sorted(Comparator.comparingInt(HintLevelVo::level).reversed()
                        .thenComparing(HintLevelVo::userId))
                .toList();
    }
}
