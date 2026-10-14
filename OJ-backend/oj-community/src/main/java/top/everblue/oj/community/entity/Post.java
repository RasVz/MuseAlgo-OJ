package top.everblue.oj.community.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * <p>
 * 文章/题解/博客表
 * </p>
 *
 * @author RasVz
 * @since 2025-12-12
 */

@Data
@TableName("post")
public class Post {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String title;
    private String content;
    private String summary;
    private Long authorId;
    private Long problemId;
    private Long contestId;
    private Long classId;
    private Integer type;
    private String category;
    private String tags;
    private String images;
    private Integer isTop;
    private Integer isEssence;
    private Integer isOfficial;
    private Integer views;
    private Integer likes;
    private Integer commentCount;
    private Integer privacy;
    
    // 以下为新增字段
    private Integer dislikes;
    private Integer favorites;
    private Double score;
    
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime hotCalculatedAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime updateTime;

    private Integer isDeleted;

    /** 仅用于文章编辑请求，不映射到 post 表。 */
    @TableField(exist = false)
    private java.util.List<Long> relatedProblemIds;
}
