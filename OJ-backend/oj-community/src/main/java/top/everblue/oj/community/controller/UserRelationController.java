package top.everblue.oj.community.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import top.everblue.oj.common.api.Result;
import top.everblue.oj.system.entity.User;
import top.everblue.oj.community.entity.UserRelation;
import top.everblue.oj.community.entity.vo.UserRelationVO;
import top.everblue.oj.community.service.UserRelationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 关注、取关与关注/粉丝列表接口。
 * <p>
 * 关注关系本身无隐私要求，因此这些接口对未登录访客开放（可查看列表，
 * 只是 {@code isFollowed} 恒为 false）。写操作才要求登录。
 * </p>
 */
@Tag(name = "关注管理", description = "关注、取关与列表查询")
@RestController
@RequestMapping("/user-relation")
@RequiredArgsConstructor
public class UserRelationController {

    private final UserRelationService userRelationService;

    /**
     * 取当前登录用户 ID，未登录返回 {@code null}。
     * <p>
     * 与 {@link top.everblue.oj.common.utils.SecurityUtils#getUserId()} 的真实差异：
     * 本方法只认 {@link User} 主对象，而前者走 {@code AuthenticatedUser} 标记接口。
     * 两者在正常登录态下结果相同，差异只在主对象类型不符时——
     * {@link User} 是 oj-system 的实体，本模块直接依赖它属于跨模块耦合；
     * 前者则由 oj-framework 提供、与业务模块无关。
     * <b>新增代码请优先用 {@code SecurityUtils}</b>，本方法保留是因为它已在关注相关接口中沿用，
     * 且整段 {@code try/catch} 吞异常的写法在本类「允许匿名」的接口语义下更直白。
     * </p>
     *
     * @return 用户 ID，未登录或主对象类型不符时为 {@code null}
     */
    private Long getCurrentUserId() {
        try {
            Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
            if (principal instanceof User) return ((User) principal).getId();
        } catch (Exception ignored) {}
        return null;
    }

    /** 关注 / 取关 */
    @Operation(summary = "关注/取关用户")
    @PostMapping("/toggle")
    public Result<String> toggleFollow(@RequestBody UserRelation params) {
        Long currentUserId = getCurrentUserId();
        if (currentUserId == null) return Result.error("请先登录");
        if (currentUserId.equals(params.getFollowedId())) return Result.error("不能关注自己");
        return Result.success(userRelationService.toggleFollow(currentUserId, params.getFollowedId()));
    }

    @Operation(summary = "获取关注列表")
    @GetMapping("/following/{userId}")
    public Result<Page<UserRelationVO>> getFollowing(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size) {
        Page<UserRelationVO> p = new Page<>(page, size);
        return Result.success(userRelationService.selectFollowingList(p, userId, getCurrentUserId()));
    }

    @Operation(summary = "获取粉丝列表")
    @GetMapping("/followers/{userId}")
    public Result<Page<UserRelationVO>> getFollowers(
            @PathVariable Long userId,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size) {
        Page<UserRelationVO> p = new Page<>(page, size);
        return Result.success(userRelationService.selectFollowerList(p, userId, getCurrentUserId()));
    }
}
