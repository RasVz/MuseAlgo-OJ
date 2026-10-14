package top.everblue.oj.community.service;

import top.everblue.oj.community.entity.Comment;
import com.baomidou.mybatisplus.extension.service.IService;
import top.everblue.oj.community.entity.dto.CommentDTO;
import top.everblue.oj.community.entity.vo.CommentVO;
import top.everblue.oj.community.comment.CommentTarget;

import java.util.List;

/**
* @author RasVz
* @description 针对表【comment(评论表)】的数据库操作Service
* @createDate 2026-01-15 17:38:41
*/
public interface CommentService extends IService<Comment> {
    void publishComment(CommentDTO dto);

    List<CommentVO> getCommentList(CommentTarget target);
}
