package top.everblue.oj.analytics.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import top.everblue.oj.analytics.domain.MasteryLevel;
import top.everblue.oj.analytics.domain.StudentProfile;
import top.everblue.oj.analytics.domain.StudentProfile.DimensionMastery;
import top.everblue.oj.analytics.domain.TagMastery;
import top.everblue.oj.analytics.entity.StudentProfileSnapshot;
import top.everblue.oj.analytics.entity.Tag;
import top.everblue.oj.analytics.mapper.ProfileSnapshotMapper;
import top.everblue.oj.analytics.mapper.TagMapper;
import top.everblue.oj.api.profile.ProfileQuery;
import top.everblue.oj.api.profile.ProfileQuery.DimensionSummary;
import top.everblue.oj.api.profile.ProfileQuery.WeekOverview;
import top.everblue.oj.api.profile.TagStat;
import top.everblue.oj.api.teaching.ClassQuery;
import top.everblue.oj.api.user.UserDisplayInfo;
import top.everblue.oj.api.user.UserQuery;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 画像应用服务——编排取数、计算、缓存、落库，是 Controller 与 domain/mapper 之间的唯一协调者。
 * <p>
 * 设计要点（对齐 ARCHITECTURE.md 的应用层约束）：
 * <ul>
 *   <li>不直接暴露实体给 HTTP 层；返回 {@link StudentProfile} 只读模型</li>
 *   <li>跨模块读取全部走 oj-api 契约（{@link ProfileQuery}、{@link UserQuery}）</li>
 *   <li>实时算的结果走 Redis 缓存（5 分钟），避免重复聚合</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileApplicationService {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 维度 code → 中文展示名（雷达图图例 / 报告标题用） */
    private static final Map<String, String> DIMENSION_LABELS = Map.of(
            "ALGORITHM", "算法",
            "DATA_STRUCTURE", "数据结构",
            "MATH", "数学",
            "GRAPH", "图论",
            "STRING", "字符串"
    );

    /** 实时画像 Redis 缓存 TTL */
    private static final Duration PROFILE_CACHE_TTL = Duration.ofMinutes(5);

    private final ProfileQuery profileQuery;
    private final UserQuery userQuery;
    private final ClassQuery classQuery;
    private final TagMapper tagMapper;
    private final ProfileSnapshotMapper profileSnapshotMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    // ------------------------------------------------------------------
    // 公共入口
    // ------------------------------------------------------------------

    /**
     * 实时构建当前登录学生的画像（带 5 分钟 Redis 缓存）。
     * 时间窗默认为最近 30 天。
     */
    public StudentProfile buildMyProfile() {
        return buildMyProfile(null, null);
    }

    /**
     * 实时构建当前登录学生的画像，可指定时间窗。
     */
    public StudentProfile buildMyProfile(LocalDate from, LocalDate to) {
        Long userId = top.everblue.oj.common.utils.SecurityUtils.getUserId();
        if (userId == null) {
            throw new IllegalStateException("请先登录");
        }
        return buildProfile(userId, null, from, to, true);
    }

    /**
     * 教师查看指定学生的画像。调用方需自行校验教师权限。
     */
    public StudentProfile buildStudentProfile(Long studentId, Long classId) {
        return buildStudentProfile(studentId, classId, null, null);
    }

    /**
     * 教师查看指定学生的画像，可指定时间窗。
     */
    public StudentProfile buildStudentProfile(Long studentId, Long classId, LocalDate from, LocalDate to) {
        top.everblue.oj.common.utils.SecurityUtils.requireTeacherOrAdmin();
        return buildProfile(studentId, classId, from, to, false);
    }

    // ------------------------------------------------------------------
    // 核心：组装画像
    // ------------------------------------------------------------------

    private StudentProfile buildProfile(Long studentId, Long classId,
                                        LocalDate from, LocalDate to, boolean useCache) {
        // 1. 时间窗：默认最近 30 天
        LocalDate end = to != null ? to : LocalDate.now();
        LocalDate start = from != null ? from : end.minusDays(30);
        LocalDateTime fromTs = start.atStartOfDay();
        LocalDateTime toTs = end.plusDays(1).atStartOfDay();

        // 2. 缓存（仅自查画像走缓存，教师查询实时性优先）
        String cacheKey = "profile:" + studentId + ":" + start + ":" + end;
        if (useCache) {
            Object cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached instanceof StudentProfile profile) {
                return profile;
            }
        }

        // 3. 周概览
        WeekOverview weekOverview = profileQuery.weekOverview(studentId, fromTs, toTs);

        // 4. 按 tag 明细
        List<TagStat> tagStats = profileQuery.aggregateByTag(studentId, fromTs, toTs);
        List<TagMastery> tagMasteries = tagStats.stream()
                .map(TagMastery::from)
                .sorted(Comparator.comparingInt(TagMastery::mastery).reversed())
                .toList();

        // 5. 按维度上卷 → 雷达图
        Map<String, DimensionMastery> dimensionMasteries = rollupByDimension(studentId, fromTs, toTs);

        // 6. 薄弱环节：mastery < 55 的知识点，按掌握度升序
        List<TagMastery> weakPoints = tagMasteries.stream()
                .filter(t -> t.mastery() < MasteryLevel.ADEQUATE.getMin())
                .sorted(Comparator.comparingInt(TagMastery::mastery))
                .toList();

        // 7. 班级百分位（仅当指定班级时计算；需要班级全员数据，一条批量 SQL）
        Map<String, Integer> dimensionPercentiles = classId != null
                ? computeDimensionPercentiles(studentId, classId, fromTs, toTs, dimensionMasteries)
                : null;

        // 8. 学生与班级展示信息（走只读契约；remark 优先）
        UserDisplayInfo student = userQuery.findDisplayInfo(studentId).orElse(null);
        String studentName = resolveStudentName(student, studentId, classId);
        String className = resolveClassName(classId);

        StudentProfile profile = new StudentProfile(
                studentId, studentName, classId, className,
                start, end,
                weekOverview,
                tagMasteries,
                dimensionMasteries,
                dimensionPercentiles,
                weakPoints
        );

        if (useCache) {
            redisTemplate.opsForValue().set(cacheKey, profile, PROFILE_CACHE_TTL);
        }
        return profile;
    }

    /**
     * 把按 tag 的统计上卷到知识域维度。
     * <p>
     * 即使某维度没提交，也要在雷达图上显示 0 分（保证雷达图维度完整）。
     */
    private Map<String, DimensionMastery> rollupByDimension(Long studentId,
                                                            LocalDateTime fromTs, LocalDateTime toTs) {
        Map<String, DimensionSummary> raw = profileQuery.aggregateByDimension(studentId, fromTs, toTs);

        // 以 tag 字典里的知识域为基准，保证雷达图维度稳定（不随数据波动）
        List<Tag> dimensions = tagMapper.selectDimensions();
        Map<String, DimensionMastery> result = new ConcurrentHashMap<>();
        for (Tag dim : dimensions) {
            DimensionSummary summary = raw.getOrDefault(dim.getDimension(),
                    new DimensionSummary(0, 0, 0, 0));
            int mastery = computeDimensionMastery(summary);
            result.put(dim.getDimension(), new DimensionMastery(
                    dim.getDimension(),
                    DIMENSION_LABELS.getOrDefault(dim.getDimension(), dim.getName()),
                    summary.attempted(),
                    summary.passed(),
                    mastery,
                    MasteryLevel.of(mastery)
            ));
        }
        return result;
    }

    /**
     * 维度级掌握度：复用 MasteryCalculator 的权重思路，
     * 但维度层没有 firstTry / difficulty 明细，用通过率为主、近期活跃为辅的简化版。
     */
    private static int computeDimensionMastery(DimensionSummary s) {
        if (s.attempted() == 0) return 0;
        double passRate = (double) s.passed() / s.attempted();
        // 简化：维度层 mastery = 80% 通过率 + 20% 一次通过率
        double firstTry = s.attempted() == 0 ? 0 : (double) s.firstTryPassed() / s.attempted();
        int mastery = (int) Math.round(100 * (0.8 * passRate + 0.2 * firstTry));
        return Math.min(mastery, 100);
    }

    // ------------------------------------------------------------------
    // 班级百分位
    // ------------------------------------------------------------------

    /**
     * 计算目标学生在班级内各知识域的百分位（0-100）。
     * <p>
     * 定义：百分位 = 班级中该维度 mastery 低于目标学生的同学数 / 班级总人数 × 100。
     * 例如 78 表示"超过班级 78% 的同学"。
     * <p>
     * 性能：班级学生 id 一次查询 + 全员维度统计一条批量 SQL，再在内存里算 mastery。
     * 对没有提交记录的同学，该维度 mastery 视为 0（计入百分位分母）。
     */
    private Map<String, Integer> computeDimensionPercentiles(
            Long studentId, Long classId, LocalDateTime fromTs, LocalDateTime toTs,
            Map<String, DimensionMastery> myDimensions) {
        try {
            // 1. 班级全员 id（走 oj-api 契约）
            List<Long> memberIds = classQuery.findStudentIds(classId);
            if (memberIds.isEmpty()) {
                return null;
            }

            // 2. 全员维度统计（一条批量 SQL）
            Map<Long, Map<String, DimensionSummary>> all =
                    profileQuery.aggregateClassByDimension(memberIds, fromTs, toTs);

            // 3. 每个维度：目标学生的 mastery vs 班级其他人的 mastery
            Map<String, Integer> percentiles = new HashMap<>();
            int totalMembers = memberIds.size();
            for (Map.Entry<String, DimensionMastery> entry : myDimensions.entrySet()) {
                String dimension = entry.getKey();
                int myMastery = entry.getValue().mastery();

                int belowCount = 0;
                for (Long memberId : memberIds) {
                    if (memberId.equals(studentId)) {
                        continue; // 不与自己比较
                    }
                    DimensionSummary other = all.getOrDefault(memberId, Map.of())
                            .get(dimension);
                    int otherMastery = other == null ? 0 : computeDimensionMastery(other);
                    if (otherMastery < myMastery) {
                        belowCount++;
                    }
                }
                // 分母用 totalMembers - 1（排除自己）；班级只有 1 人时给 null 无意义
                int denominator = totalMembers - 1;
                percentiles.put(dimension, denominator > 0
                        ? (int) Math.round(belowCount * 100.0 / denominator)
                        : null);
            }
            return percentiles;
        } catch (Exception e) {
            // 百分位是增值信息，失败不应阻断画像主流程
            log.warn("班级百分位计算失败，降级为 null。studentId={}, classId={}: {}",
                    studentId, classId, e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 快照：定时任务调用（阶段 4）
    // ------------------------------------------------------------------

    /**
     * 把画像序列化后幂等落库为周快照（upsert，重复生成同周期数据时更新）。
     * 供定时任务和报告生成复用。
     */
    public StudentProfileSnapshot saveSnapshot(StudentProfile profile) {
        try {
            String json = JSON.writeValueAsString(profile);
            StudentProfileSnapshot snapshot = new StudentProfileSnapshot();
            snapshot.setStudentId(profile.studentId());
            snapshot.setClassId(profile.classId());
            snapshot.setPeriodStart(profile.periodStart());
            snapshot.setPeriodEnd(profile.periodEnd());
            snapshot.setSnapshotJson(json);
            profileSnapshotMapper.upsert(snapshot);
            // upsert 命中 UPDATE 分支时 MyBatis 不会回填自增主键，按唯一键查回 id
            if (snapshot.getId() == null) {
                StudentProfileSnapshot existing = profileSnapshotMapper.selectOne(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<StudentProfileSnapshot>()
                                .eq(StudentProfileSnapshot::getStudentId, profile.studentId())
                                .eq(StudentProfileSnapshot::getPeriodStart, profile.periodStart()));
                if (existing != null) {
                    snapshot.setId(existing.getId());
                    snapshot.setReportUrl(existing.getReportUrl());
                }
            }
            return snapshot;
        } catch (JsonProcessingException e) {
            log.error("画像快照序列化失败 studentId={}", profile.studentId(), e);
            throw new IllegalStateException("画像快照保存失败", e);
        }
    }

    /**
     * 报告生成专用：构建画像（教师视角，不走个人缓存），并允许调用方后续自行决定
     * 是否落快照。与 {@link #buildStudentProfile} 的区别仅在不强制每次重新取数时的
     * 缓存策略，当前实现等价。
     */
    public StudentProfile buildForReport(Long studentId, Long classId,
                                         LocalDate from, LocalDate to) {
        top.everblue.oj.common.utils.SecurityUtils.requireTeacherOrAdmin();
        return buildProfile(studentId, classId, from, to, false);
    }

    /**
     * 系统内部构建画像（定时任务用，无登录上下文，跳过权限校验）。
     * 仅限可信调用方（如 WeeklyProfileReportTask）。
     */
    public StudentProfile buildForReportInternal(Long studentId, Long classId,
                                                 LocalDate from, LocalDate to) {
        return buildProfile(studentId, classId, from, to, false);
    }

    // ------------------------------------------------------------------
    // 展示名解析（班级 remark 优先 > 昵称 > 用户名）
    // ------------------------------------------------------------------

    /**
     * 学生显示名解析：班级 remark 优先（教师在班级里给学生起的名字，通常是真名），
     * 其次昵称，最后用户名。remark 查询失败时静默降级，不影响画像主流程。
     */
    private String resolveStudentName(UserDisplayInfo student, Long studentId, Long classId) {
        if (classId != null && studentId != null) {
            try {
                // 班级 remark 优先（走 oj-api 契约）
                String remark = classQuery.findStudentRemark(classId, studentId).orElse(null);
                if (remark != null) {
                    return remark;
                }
            } catch (Exception e) {
                log.debug("查询班级 remark 失败，降级为昵称/用户名: {}", e.getMessage());
            }
        }
        if (student == null) return "未知学生";
        if (student.nickname() != null && !student.nickname().isBlank()) {
            return student.nickname();
        }
        return student.username();
    }

    private String resolveClassName(Long classId) {
        if (classId == null) return null;
        ClassQuery.ClassInfo clazz = classQuery.findById(classId).orElse(null);
        return clazz != null ? clazz.name() : null;
    }
}