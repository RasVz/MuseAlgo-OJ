package top.everblue.oj.community.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import top.everblue.oj.common.api.Result;
import top.everblue.oj.community.entity.dto.CommentDTO;
import top.everblue.oj.community.entity.vo.CommentVO;
import top.everblue.oj.community.comment.CommentTarget;
import top.everblue.oj.community.service.CommentService;
import top.everblue.oj.community.interaction.CommunityInteractionService;
import top.everblue.oj.community.interaction.InteractionAction;
import top.everblue.oj.community.interaction.InteractionCommand;
import top.everblue.oj.community.interaction.InteractionTargetType;
import top.everblue.oj.common.utils.SecurityUtils;

import java.util.List;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 评论的发表与查询接口。
 * <p>
 * 评论业务的规则（目标校验、楼中楼、通知）全在
 * {@link top.everblue.oj.community.service.impl.CommentServiceImpl}，
 * 本类只做鉴权取用户、参数绑定与异常到响应码的翻译。
 * </p>
 * <p>
 * <b>业务异常以 200 + 错误消息返回，而非 HTTP 错误码：</b>
 * 前端按统一响应体 {@code Result} 处理错误分支，
 * 若这里改成抛出 4xx，全站前端都需要额外适配。不要擅自改成异常处理器。
 * </p>
 */
@Tag(name = "评论管理", description = "评论的发表与查询")
@RestController
@RequestMapping("/comment")
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;

    private final CommunityInteractionService interactionService;

    // 1. 发布评论
    @Operation(summary = "发布评论")
    @PostMapping("/add")
    public Result<String> addComment(@RequestBody @Validated CommentDTO dto) {
        try {
            commentService.publishComment(dto);
            return Result.success("评论成功");
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    // 2. 获取评论列表
    @Operation(summary = "获取评论列表")
    @GetMapping("/list")
    public Result<List<CommentVO>> getList(
            @RequestParam Long entityId,
            @RequestParam(defaultValue = "1") Integer entityType) {
        try {
            return Result.success(commentService.getCommentList(new CommentTarget(entityId, entityType)));
        } catch (IllegalArgumentException e) {
            return Result.error(e.getMessage());
        }
    }

    // 3. 点赞/取消点赞接口
    // 前端请求: POST /comment/like?commentId=123
    @Operation(summary = "点赞评论")
    @PostMapping("/like")
    public Result<String> toggleLike(@RequestParam Long commentId, @RequestParam(required = false) Boolean active) {
        Long userId = SecurityUtils.getUserId();
        if (userId == null) return Result.error(401, "请先登录");
        InteractionCommand command = new InteractionCommand(userId, InteractionTargetType.COMMENT, commentId, InteractionAction.LIKE);
        try {
            boolean targetState = active != null ? active : !interactionService.isActive(command);
            if (targetState) interactionService.activate(command);
            else interactionService.deactivate(command);
            return Result.success("操作成功");
        } catch (IllegalArgumentException e) {
            return Result.error(404, e.getMessage());
        }
    }
}
