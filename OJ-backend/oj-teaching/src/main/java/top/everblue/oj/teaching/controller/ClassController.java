package top.everblue.oj.teaching.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import top.everblue.oj.common.utils.SecurityUtils;
import top.everblue.oj.api.contest.ClassContestPageRequest;
import top.everblue.oj.api.contest.ClassContestQuery;
import top.everblue.oj.api.contest.ClassContestSummary;
import top.everblue.oj.api.submission.HomeworkSubmissionPageRequest;
import top.everblue.oj.api.submission.HomeworkSubmissionQuery;
import top.everblue.oj.api.submission.HomeworkSubmissionSummary;
import top.everblue.oj.api.submission.PageResult;
import top.everblue.oj.teaching.entity.Class;
import top.everblue.oj.teaching.entity.ClassStudent;
import top.everblue.oj.api.problem.ProblemListView;
import top.everblue.oj.teaching.service.ClassService;
import top.everblue.oj.teaching.service.ClassStudentService;
import top.everblue.oj.api.problem.ProblemListQuery;
import top.everblue.oj.common.api.Result;
import top.everblue.oj.common.annotation.AuditLog;
import top.everblue.oj.teaching.application.ClassMembershipApplicationService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.stream.Collectors;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 班级表 前端控制器
 */
@Tag(name = "班级管理", description = "班级创建、加入、作业查看")
@RestController
@RequiredArgsConstructor
@RequestMapping("/class")
public class ClassController {
    private final ClassService ClassService;

    private final ClassStudentService classStudentService;

    private final ProblemListQuery problemListQuery;

    private final HomeworkSubmissionQuery homeworkSubmissionQuery;

    private final ClassContestQuery classContestQuery;

    private final ClassMembershipApplicationService classMembershipApplicationService;

    @Operation(summary = "获取邀请码")
    @GetMapping("/inviteCode")
    public Result<String> getInviteCode(@RequestParam Long classId) {
        String code = ClassService.createInviteCode(classId);
        return Result.success("生成成功", code);
    }

    @Operation(summary = "通过邀请码加入班级")
    @PostMapping("/joinByCode")
    public Result<String> joinByInviteCode(@RequestBody Map<String, String> params) {
        String code = params.get("code");
        // 获取当前登录用户ID
        Long userId = SecurityUtils.getUserId();
        if (userId == null) {
            return Result.error(401, "请先登录");
        }

        Integer message = ClassService.joinByInviteCode(userId, code);
        if(message == 0) {
            return Result.error("邀请码无效或已过期");
        } else if(message == 1) {
            return Result.error("您已在班级中，无需重复加入");
        } else {
            return Result.success("加入班级成功");
        }
    }

    // 创建班级
    @Operation(summary = "创建班级")
    @AuditLog(module = "class", action = "create_class")
    @PostMapping("/create")
    public Result<String> create(@RequestBody Class Class) {
        // 简单的后端校验
        if (Class.getName() == null || Class.getTeacherId() == null) {
            return Result.error("参数不完整");
        }

        boolean success = ClassService.createClass(Class);
        if (success) {
            return Result.success("创建成功");
        } else {
            return Result.error("创建失败");
        }
    }

    @Operation(summary = "教师创建的班级列表")
    @GetMapping("/list")
    public Result<List<Class>> listCreated(@RequestParam Long teacherId) {
        LambdaQueryWrapper<Class> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Class::getTeacherId, teacherId);
        wrapper.orderByDesc(Class::getCreateTime); // 按创建时间倒序

        List<Class> list = ClassService.list(wrapper);
        return Result.success(list);
    }

    @Operation(summary = "我加入的班级列表")
    @GetMapping("/my")
    public Result<IPage<Class>> listMyClasses(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size
    ) {
        // 1. 获取userId
        Long userId = SecurityUtils.getUserId();

        // 2. 如果没拿到 ID，说明没登录或者 Token 过期，返回401
        if (userId == null) {
            return Result.error(401, "请先登录");
        }

        IPage<Class> result = ClassService.getJoinedClasses(page, size, userId);
        return Result.success(result);
    }

    /**
     * 获取当前学生所有班级的作业列表
     * @param userId 学生ID
     */
    @Operation(summary = "我的作业列表")
    @GetMapping("/homework/my")
    public Result<List<Map<String, Object>>> getMyHomeworks(@RequestParam Long userId) {
        // 1. 先查学生加入了哪些班级
        LambdaQueryWrapper<ClassStudent> csWrapper = new LambdaQueryWrapper<>();
        csWrapper.eq(ClassStudent::getStudentId, userId);
        List<ClassStudent> myClasses = classStudentService.list(csWrapper);

        if (myClasses == null || myClasses.isEmpty()) {
            return Result.success(new ArrayList<>());
        }

        // 提取所有班级ID
        List<Long> classIds = myClasses.stream()
                .map(ClassStudent::getClassId)
                .collect(Collectors.toList());

        // 2. 查这些班级下的题单（走 oj-api 只读契约）
        List<ProblemListView> homeworks = problemListQuery.findByClassIds(classIds);

        // 3. 组装返回数据（关联上班级名称）
        // 先只返回作业列表，前端通过 classId 自己去匹配班级名，或者后端再查一次 Class 表（简单的组装示例）
        List<Map<String, Object>> resultList = new ArrayList<>();

        // 预加载所有涉及的班级信息，避免循环查库
        List<Class> groups = ClassService.listByIds(classIds);
        Map<Long, String> groupNameMap = groups.stream()
                .collect(Collectors.toMap(Class::getId, Class::getName));

        for (ProblemListView hw : homeworks) {
            Map<String, Object> map = new java.util.HashMap<>();
            map.put("id", hw.id());
            map.put("title", hw.title());
            map.put("classId", hw.classId());
            map.put("className", groupNameMap.getOrDefault(hw.classId(), "未知班级"));
            map.put("createTime", hw.createTime());
            resultList.add(map);
        }

        return Result.success(resultList);
    }

    @Operation(summary = "作业完成情况概览")
    @GetMapping("/{classId}/homework/{homeworkId}/status/overview")
    public Result<Map<String, Object>> getHomeworkStatusOverview(
            @PathVariable Long classId,
            @PathVariable Long homeworkId
    ) {
        Long currentUserId = SecurityUtils.getUserId();
        if (currentUserId == null) {
            return Result.error(401, "请先登录");
        }

        Class classInfo = ClassService.getById(classId);
        if (classInfo == null) {
            return Result.error("班级不存在");
        }
        if (!currentUserId.equals(classInfo.getTeacherId()) && !SecurityUtils.isAdmin()) {
            return Result.error(403, "无权限查看该班级作业完成情况");
        }

        ProblemListView homework = problemListQuery.findById(homeworkId).orElse(null);
        if (homework == null || !classId.equals(homework.classId())) {
            return Result.error("作业不存在或不属于当前班级");
        }

        return Result.success(problemListQuery.getHomeworkStatusOverview(classId, homeworkId));
    }

    @Operation(summary = "作业提交记录")
    @GetMapping("/{classId}/homework/{homeworkId}/status/submissions")
    public Result<IPage<HomeworkSubmissionSummary>> getHomeworkStatusSubmissions(
            @PathVariable Long classId,
            @PathVariable Long homeworkId,
            @RequestParam(defaultValue = "1") Integer currentPage,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) Long studentId,
            @RequestParam(required = false) Long problemId
    ) {
        Long currentUserId = SecurityUtils.getUserId();
        if (currentUserId == null) {
            return Result.error(401, "请先登录");
        }

        Class classInfo = ClassService.getById(classId);
        if (classInfo == null) {
            return Result.error("班级不存在");
        }
        if (!currentUserId.equals(classInfo.getTeacherId()) && !SecurityUtils.isAdmin()) {
            return Result.error(403, "无权限查看该班级作业提交记录");
        }

        ProblemListView homework = problemListQuery.findById(homeworkId).orElse(null);
        if (homework == null || !classId.equals(homework.classId())) {
            return Result.error("作业不存在或不属于当前班级");
        }

        PageResult<HomeworkSubmissionSummary> resultPage = homeworkSubmissionQuery.findPage(new HomeworkSubmissionPageRequest(
                classId, homeworkId, studentId, problemId, currentPage, pageSize, homework.startTime(), homework.endTime()));
        Page<HomeworkSubmissionSummary> result = new Page<>(resultPage.page(), resultPage.size(), resultPage.total());
        result.setRecords(resultPage.records());
        return Result.success(result);
    }

    @Operation(summary = "班级成员列表")
    @GetMapping("/members")
    @PreAuthorize("hasAuthority('class:member:manage')")
    public Result<IPage<Map<String, Object>>> getMembers(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam Long classId,
            @RequestParam(required = false) String keyword
    ) {
        IPage<Map<String, Object>> result = classMembershipApplicationService.getMembers(page, size, classId, keyword);
        return Result.success(result);
    }

    // === 修改成员备注 ===
    @Operation(summary = "修改成员备注")
    @AuditLog(module = "class", action = "update_member_remark")
    @PutMapping("/member/remark")
    @PreAuthorize("hasAuthority('class:member:manage')")
    public Result<String> updateMemberRemark(@RequestBody Map<String, Object> params) {
        Long classId = Long.parseLong(params.get("classId").toString());
        Long userId = Long.parseLong(params.get("userId").toString());
        String remark = (String) params.get("remark");

        classMembershipApplicationService.updateRemark(classId, userId, remark);
        return Result.success("备注更新成功");
    }

    // === 移出成员 ===
    @Operation(summary = "移出成员")
    @AuditLog(module = "class", action = "remove_member")
    @DeleteMapping("/member/remove")
    @PreAuthorize("hasAuthority('class:member:manage')")
    public Result<String> removeMember(@RequestBody Map<String, Object> params) {
        Long classId = Long.parseLong(params.get("classId").toString());
        Long userId = Long.parseLong(params.get("userId").toString());

        classMembershipApplicationService.removeMember(classId, userId);
        return Result.success("已移出该成员");
    }

    @Operation(summary = "班级详情")
    @GetMapping("/{id}")
    public Result<Class> getDetail(@PathVariable Long id) {
        Class Class = ClassService.getById(id);
        if (Class == null) {
            return Result.error("班级不存在");
        }
        return Result.success(Class);
    }

    /**
     * 分页获取班级比赛列表
     * @param classId 班级ID
     * @param currentPage 当前页码
     * @param pageSize 每页数量
     * @param keyword 搜索关键字 (比赛名称)
     */
    @Operation(summary = "班级比赛列表")
    @GetMapping("/{classId}/contest")
    public Result<Page<ClassContestSummary>> getClassContestList(
            @PathVariable Long classId,
            @RequestParam(defaultValue = "1") Integer currentPage,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String sortField,
            @RequestParam(required = false) String sortOrder) {
        PageResult<ClassContestSummary> resultPage = classContestQuery.findPage(new ClassContestPageRequest(
                classId, currentPage, pageSize, keyword, sortField, sortOrder));
        Page<ClassContestSummary> pageData = new Page<>(resultPage.page(), resultPage.size(), resultPage.total());
        pageData.setRecords(resultPage.records());
        return Result.success(pageData);
    }
}
