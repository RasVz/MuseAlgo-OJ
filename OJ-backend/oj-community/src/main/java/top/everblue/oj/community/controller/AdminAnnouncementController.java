package top.everblue.oj.community.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.everblue.oj.common.api.Result;
import top.everblue.oj.common.annotation.AuditLog;
import top.everblue.oj.common.utils.SecurityUtils;
import top.everblue.oj.community.entity.Post;
import top.everblue.oj.community.service.PostService;

import java.time.LocalDateTime;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 公告管理（管理员）：公告即 post.category=announcement。
 * <p>
 * <b>公告即 {@code post.category = 'announcement'} 的一类动态</b>，不另建公告表：
 * 复用同一张表即复用了评论、点赞、软删除等整套既有能力。
 * 代价是公告与普通动态共用 {@code views}、{@code likes} 等计数字段，
 * 以及共用作者维度（{@code author_id} 记的是发布公告的管理员）。
 * </p>
 * <p>
 * 权限用类级 {@code @PreAuthorize} 声明：类内全部接口都是管理动作，
 * 不存在「同一类里既有用户接口又有管理接口」的场景，
 * 放在类上比逐方法加更省事，也更难漏。
 * </p>
 */
@Tag(name = "公告管理", description = "管理员公告发布与维护")
@RestController
@RequestMapping("/admin/announcements")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminAnnouncementController {

    private final PostService postService;

    @Operation(summary = "公告列表")
    @GetMapping("/list")
    public Result<Page<Post>> list(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer size) {
        Page<Post> result = postService.page(
                new Page<>(page, size),
                new LambdaQueryWrapper<Post>()
                        .eq(Post::getCategory, "announcement")
                        .orderByDesc(Post::getIsTop)
                        .orderByDesc(Post::getCreateTime));
        return Result.success(result);
    }

    @Operation(summary = "发布/编辑公告")
    @AuditLog(module = "announcement", action = "save_announcement")
    @PostMapping("/save")
    public Result<String> save(@RequestBody Post post) {
        if (post.getTitle() == null || post.getTitle().isBlank()
                || post.getContent() == null || post.getContent().isBlank()) {
            return Result.error("标题与内容不能为空");
        }
        post.setCategory("announcement");
        if (post.getId() == null) {
            post.setAuthorId(SecurityUtils.getUserId());
            post.setCreateTime(LocalDateTime.now());
            post.setIsDeleted(0);
            if (post.getIsTop() == null) post.setIsTop(0);
            if (post.getPrivacy() == null) post.setPrivacy(1);
            postService.save(post);
        } else {
            Post update = new Post();
            update.setId(post.getId());
            update.setTitle(post.getTitle());
            update.setContent(post.getContent());
            update.setIsTop(post.getIsTop() == null ? 0 : post.getIsTop());
            postService.updateById(update);
        }
        return Result.success("保存成功");
    }

    @Operation(summary = "删除公告")
    @AuditLog(module = "announcement", action = "delete_announcement")
    @PostMapping("/delete/{id}")
    public Result<String> delete(@PathVariable Long id) {
        postService.removeById(id);
        return Result.success("已删除");
    }
}
