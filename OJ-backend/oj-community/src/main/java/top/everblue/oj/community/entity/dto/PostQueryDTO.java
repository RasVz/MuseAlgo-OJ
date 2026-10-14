package top.everblue.oj.community.entity.dto;

import lombok.Data;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 热门帖子查询参数 DTO
 */
@Data
public class PostQueryDTO {
    
    /**
     * 分区/分类名（可为空，表示查询全站热门）
     */
    private String category;

    /**
     * 页码
     */
    @Min(value = 1, message = "页码最小为1")
    private int page = 1;

    /**
     * 每页数量
     */
    @Min(value = 1, message = "每页数量最小为1")
    @Max(value = 50, message = "每页数量最多为50")
    private int size = 20;
}
