package com.musealgo.oj.community.service;

import com.musealgo.oj.community.entity.Comment;
import com.baomidou.mybatisplus.spring.service.IService;
import com.musealgo.oj.community.entity.dto.CommentDTO;
import com.musealgo.oj.community.entity.vo.CommentPageVO;
import com.musealgo.oj.community.entity.vo.CommentVO;
import com.musealgo.oj.community.comment.CommentTarget;

import java.util.List;

/**
* @author RasVz
* @description 针对表【comment(评论表)】的数据库操作Service
* @createDate 2026-01-15 17:38:41
*/
public interface CommentService extends IService<Comment> {
    void publishComment(CommentDTO dto);

    List<CommentVO> getCommentList(CommentTarget target);

    /**
     * 顶层评论分页，附带每页顶层评论下的全部回复。
     *
     * @param page 页码，从 1 开始
     * @param size 每页顶层评论数，服务端上限 50
     */
    CommentPageVO getCommentPage(CommentTarget target, int page, int size);
}
