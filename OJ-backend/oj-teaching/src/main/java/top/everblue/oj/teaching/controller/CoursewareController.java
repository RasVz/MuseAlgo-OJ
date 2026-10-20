package top.everblue.oj.teaching.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import top.everblue.oj.common.api.Result;
import top.everblue.oj.common.annotation.AuditLog;
import top.everblue.oj.common.utils.SecurityUtils;
import top.everblue.oj.teaching.entity.Courseware;
import top.everblue.oj.system.entity.User;
import top.everblue.oj.teaching.entity.vo.CoursewareListVO;
import top.everblue.oj.api.user.UserDisplayInfo;
import top.everblue.oj.api.user.UserQuery;
import top.everblue.oj.teaching.service.CoursewareService;
import top.everblue.oj.teaching.service.ClassCoursewareService;
import top.everblue.oj.teaching.SlidevBuilderService;

import java.util.*;
import java.util.stream.Collectors;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 课件管理接口：详情、列表、增改删，以及 Markdown 上传后自动构建为 Slidev 网页。
 * <p>
 * <strong>本类的权限判定必须自己写，不依赖 SecurityConfig 的白名单。</strong>
 * 课件相关路径在 SecurityConfig 中是放行的（公开课件页需要匿名可嵌），
 * 因此这里的每个数据接口都得自行判权：
 * 私有课件仅创建者 / 管理员 / 绑定班级的学习者可读，改删仅创建者或管理员。
 * 新增接口时若漏掉这一步，等于在放行路径上开了个越权读写口子。
 * </p>
 * <p>
 * 三处防篡改措施缺一不可：
 * </p>
 * <ol>
 *   <li>创建时 {@code teacherId} 强制取自登录态，不接受入参——否则可以冒充他人上传课件。</li>
 *   <li>更新时把它置 null，<strong>不接受请求体覆盖</strong>：否则调用方可以把课件「转赠」给别人，
 *       转赠后再改就名正言顺地越权了。</li>
 *   <li>改删前用 {@code requireOwnerOrAdmin} 校验归属，而不是先改后查。</li>
 * </ol>
 * <p>
 * 列表的两个查询都要防 N+1：教师信息与「被几个班级使用」都是先收集 id 集合再批量查，
 * 而不是逐条循环里查数据库。删除 {@code coursewareIds} 的批量查询会让课件多了以后页面积雪式变慢。
 * </p>
 * <p>
 * <code>uploadAndBuildMarkdown</code> 是<strong>同步阻塞</strong>的：构建要调起 Node 跑 slidev，
 * 通常耗时几十秒。它复用了 HTTP 请求线程，因此高峰期并发构建会占满线程池。
 * 目前靠「只有教师会传课件」这个前提兜着；如果要开放给更多角色，改为异步任务 + 轮询是必须的下一步。
 * 同时注意它返回的是课件 URL 而不是创建课件记录，教师还需再走一次 {@code /add} 才能正式发布。
 * </p>
 */
@Slf4j
@Tag(name = "课件管理", description = "课件创建、列表与排序")
@RestController
@RequiredArgsConstructor
@RequestMapping("/courseware")
public class CoursewareController {

    private final CoursewareService coursewareService;

    private final ClassCoursewareService classCoursewareService;

    private final UserQuery userQuery;

    /**
     * 获取当前登录用户ID
     */
    private Long getCurrentUserId() {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.getPrincipal() instanceof User) {
                return ((User) authentication.getPrincipal()).getId();
            }
        } catch (Exception e) {
            log.error("获取当前用户ID失败", e);
        }
        return null;
    }

    // === 1. 获取课件详情 ===
    @Operation(summary = "课件详情")
    @GetMapping("/{id}")
    public Result<Courseware> getDetail(@PathVariable Long id) {
        Courseware courseware = coursewareService.getById(id);
        if (courseware == null) {
            return Result.error("课件不存在");
        }
        // 私有课件越权防护（P1 遗留新债修复）：
        // 公开课件（privacy=1）所有人可看；私有课件仅 owner / 管理员 / 班级内学习者可看
        if (!Integer.valueOf(1).equals(courseware.getPrivacy())) {
            Long userId = getCurrentUserId();
            boolean isOwner = userId != null && userId.equals(courseware.getTeacherId());
            if (!isOwner && !SecurityUtils.isAdmin()) {
                boolean inClass = userId != null
                        && classCoursewareService.isVisibleForMember(courseware.getId(), userId);
                if (!inClass) {
                    return Result.error(403, "无权查看该课件");
                }
            }
        }
        return Result.success(courseware);
    }

    // === 2. 获取课件列表 (支持 "只看我的" 筛选) ===
    @Operation(summary = "课件列表")
    @GetMapping("/list")
    public Result<Page<CoursewareListVO>> list(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "false") Boolean onlyMine
    ) {
        Long currentUserId = getCurrentUserId();

        Page<Courseware> pageParam = new Page<>(page, size);
        LambdaQueryWrapper<Courseware> wrapper = new LambdaQueryWrapper<>();

        // 搜索逻辑
        if (StringUtils.hasText(keyword)) {
            wrapper.like(Courseware::getTitle, keyword);
        }

        // 权限/筛选逻辑
        if (Boolean.TRUE.equals(onlyMine)) {
            // 只看我的
            if (currentUserId == null) return Result.error("未登录");
            wrapper.eq(Courseware::getTeacherId, currentUserId);
        } else {
            // 看全部：(公开的) OR (我自己创建的私有课件)
            wrapper.and(w -> {
                w.eq(Courseware::getPrivacy, 1); // 公开
                if (currentUserId != null) {
                    w.or().eq(Courseware::getTeacherId, currentUserId); // 或是我自己的
                }
            });
        }

        wrapper.orderByDesc(Courseware::getCreateTime);
        Page<Courseware> coursewarePage = coursewareService.page(pageParam, wrapper);

        // 批量查询老师信息，避免N+1
        List<Courseware> records = coursewarePage.getRecords();
        Set<Long> teacherIds = records.stream()
                .map(Courseware::getTeacherId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        Map<Long, UserDisplayInfo> userMap;
        if (!teacherIds.isEmpty()) {
            userMap = userQuery.findDisplayInfoBatch(teacherIds);
        } else {
            userMap = new HashMap<>();
        }

        // 课件使用统计：被几个班级使用（批量查询避免 N+1）
        List<Long> coursewareIds = records.stream()
                .map(Courseware::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        Map<Long, Integer> classUseCounts = coursewareIds.isEmpty() ? Map.of()
                : coursewareService.countClassUses(coursewareIds);

        List<CoursewareListVO> voRecords = records.stream().map(c -> {
            CoursewareListVO vo = new CoursewareListVO();
            BeanUtils.copyProperties(c, vo);

            UserDisplayInfo teacher = userMap.get(c.getTeacherId());
            if (teacher != null) {
                // 显示优先级：昵称 > 用户名
                String teacherName = StringUtils.hasText(teacher.nickname())
                        ? teacher.nickname()
                        : teacher.username();
                vo.setTeacherName(teacherName);
                vo.setTeacherAvatar(teacher.avatar());
            }

            vo.setClassUseCount(classUseCounts.getOrDefault(c.getId(), 0));

            return vo;
        }).collect(Collectors.toList());

        Page<CoursewareListVO> voPage = new Page<>(coursewarePage.getCurrent(), coursewarePage.getSize(), coursewarePage.getTotal());
        voPage.setRecords(voRecords);

        return Result.success(voPage);
    }

    // === 3. 创建课件 ===
    @Operation(summary = "创建课件")
    @AuditLog(module = "courseware", action = "create_courseware")
    @PostMapping("/add")
    public Result<String> add(@RequestBody Courseware courseware) {
        SecurityUtils.requireTeacherOrAdmin();
        Long userId = getCurrentUserId();
        if (userId == null) return Result.error("未登录");

        courseware.setTeacherId(userId);

        boolean saved = coursewareService.save(courseware);
        return saved ? Result.success("创建成功") : Result.error("创建失败");
    }

    // === 4. 更新课件 ===
    @Operation(summary = "更新课件")
    @AuditLog(module = "courseware", action = "update_courseware")
    @PostMapping("/update")
    public Result<String> update(@RequestBody Courseware courseware) {
        SecurityUtils.requireTeacherOrAdmin();
        if (courseware.getId() == null) return Result.error("ID不能为空");

        Courseware existing = coursewareService.getById(courseware.getId());
        if (existing == null) return Result.error("课件不存在");
        // 越权防护：仅课件创建者或管理员可更新（P1 技术债修复）
        SecurityUtils.requireOwnerOrAdmin(existing.getTeacherId());

        // 防篡改：teacherId 只能由后端维护，不接受请求体覆盖
        courseware.setTeacherId(null);

        boolean updated = coursewareService.updateById(courseware);
        return updated ? Result.success("更新成功") : Result.error("更新失败");
    }

    // === 5. 删除课件 ===
    @Operation(summary = "删除课件")
    @AuditLog(module = "courseware", action = "delete_courseware")
    @PostMapping("/delete/{id}")
    public Result<String> delete(@PathVariable Long id) {
        SecurityUtils.requireTeacherOrAdmin();
        Courseware existing = coursewareService.getById(id);
        if (existing == null) return Result.error("课件不存在");
        // 越权防护：仅课件创建者或管理员可删除（P1 技术债修复）
        SecurityUtils.requireOwnerOrAdmin(existing.getTeacherId());

        boolean removed = coursewareService.removeById(id);
        return removed ? Result.success("删除成功") : Result.error("删除失败");
    }

    private final SlidevBuilderService slidevBuilderService;

    /**
     * 老师上传 Markdown 课件，自动构建为本地 Slidev 网页
     */
    @Operation(summary = "上传 Markdown 课件并生成 Slidev")
    @AuditLog(module = "courseware", action = "upload_markdown")
    @PostMapping("/upload-md")
    public Result<String> uploadAndBuildMarkdown(@RequestParam("file") MultipartFile file) {
        SecurityUtils.requireTeacherOrAdmin();

        // 校验一下是不是 md 文件
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".md")) {
            return Result.error("请上传 .md 格式的 Markdown 文件");
        }

        try {
            // 调用 Service 生成网页，会阻塞等待几十秒 (根据服务器性能决定)
            String presentationUrl = slidevBuilderService.buildPresentation(file);

            return Result.success("课件生成成功！", presentationUrl);
        } catch (Exception e) {
            log.error("课件生成失败", e);
            return Result.error("课件生成失败，请检查课件内容或联系管理员");
        }
    }
}
