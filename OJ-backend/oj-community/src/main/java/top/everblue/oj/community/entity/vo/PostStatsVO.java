package top.everblue.oj.community.entity.vo;

import lombok.Data;

/**
 * 帖子详情页右侧实时统计栏 VO
 */
@Data
public class PostStatsVO {
    
    private Integer views = 0;
    private Integer likes = 0;
    private Integer dislikes = 0;
    private Integer favorites = 0;
    private Integer comments = 0;
    
    // 当前登录用户的状态
    private Boolean userLiked = false;
    private Boolean userFaved = false;
}
