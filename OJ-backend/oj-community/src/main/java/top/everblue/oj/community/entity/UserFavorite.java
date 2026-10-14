package top.everblue.oj.community.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 收藏记录。
 * <p>
 * 采用<b>状态位软取消</b>而非取消即删：{@code status=0} 的行保留，用于统计
 * 「曾收藏过」与判断用户是否需要重新收藏时保留首次收藏时间（{@code createTime}）。
 * 若改为物理删除，收藏列表的排序语义会随取消动作抖动。
 * </p>
 * <p>
 * {@code status} 用 {@link Integer} 而非 boolean，因为需要区分「未收藏」与
 * 「已取消收藏」两种都算未收藏但业务含义不同的状态。
 * </p>
 */
@Data
@TableName("user_favorite")
public class UserFavorite {
    @TableId(type = IdType.AUTO)
    private Long id;
    
    // 收藏者ID
    private Long userId;
    
    // 被收藏内容ID
    private Long targetId;
    
    // 目标类型: 1-题目, 2-题单, 3-比赛, 4-动态
    private Integer targetType;
    
    // 收藏状态: 1-当前已收藏, 0-当前已取消
    private Integer status;
    
    // 首次收藏时间
    private LocalDateTime createTime;
    
    // 最后状态变更时间
    private LocalDateTime updateTime;
    
    // 最近一次取消时间
    private LocalDateTime cancelTime;
}
