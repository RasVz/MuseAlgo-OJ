package top.everblue.oj.community.entity.vo;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 热门榜单返回项 VO
 */
@Data
public class PostHotVO {
    private Long postId;
    private String title;
    
    /**
     * 帖子摘要 (正文前 100 字符)
     */
    private String summary;
    
    private Long authorId;
    private String category;
    private List<String> tags;

    // 实时统计数据（来自 Redis）
    private Integer likes = 0;
    private Integer favorites = 0;
    private Integer comments = 0;
    private Integer views = 0;
    
    /**
     * 热度分 (供前端调试或展示)
     */
    private Double score = 0.0;
    
    private LocalDateTime createTime;
}
