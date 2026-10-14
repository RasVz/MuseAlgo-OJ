package top.everblue.oj.community.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户关注关系（粉丝 → 博主）。
 * <p>
 * 方向是单向存储的：{@code followerId} 关注 {@code followedId}。
 * 粉丝列表与关注列表各查一次即可互为反向关系，无需再存一份对称记录，
 * 也就不会产生「A 关注 B 但 B 没关注 A」的写入不一致。
 * </p>
 */
@Data
@TableName("user_relation")
public class UserRelation implements Serializable {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    private Long followerId; // 粉丝
    private Long followedId; // 博主
    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}