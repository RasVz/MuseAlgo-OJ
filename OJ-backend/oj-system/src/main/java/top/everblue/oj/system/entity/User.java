package top.everblue.oj.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.time.LocalDateTime;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Getter;
import lombok.Setter;
import top.everblue.oj.common.security.AuthenticatedUser;

/**
 * <p>
 * 用户表
 * </p>
 *
 * @author RasVz
 * @since 2025-12-12
 */
@Getter
@Setter
@TableName("user")
@ApiModel(value = "User对象", description = "用户表")
public class User implements Serializable, AuthenticatedUser {

    private static final long serialVersionUID = 1L;

    @ApiModelProperty("主键")
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @ApiModelProperty("用户名")
    @TableField("username")
    private String username;

    @ApiModelProperty("密码")
    @TableField("password")
    private String password;

    @ApiModelProperty("昵称")
    @TableField("nickname")
    private String nickname;

    @ApiModelProperty("1-学生, 2-老师, 3-管理员")
    @TableField("role")
    private Byte role;

    @ApiModelProperty("1-禁用（登录与请求被拒绝）")
    @TableField("is_banned")
    private Byte isBanned;

    @ApiModelProperty("邮箱")
    @TableField("email")
    private String email;

    @ApiModelProperty("头像")
    @TableField("avatar")
    private String avatar;

    @ApiModelProperty("真实姓名")
    @TableField("real_name")
    private String realName;

    @ApiModelProperty("学校")
    @TableField("school")
    private String school;

    @ApiModelProperty("专业/年级")
    @TableField("major")
    private String major;

    @ApiModelProperty("签名")
    @TableField("motto")
    private String motto;

    @ApiModelProperty("博客链接")
    @TableField("blog")
    private String blog;

    @ApiModelProperty("Github链接")
    @TableField("github")
    private String github;

    @ApiModelProperty("偏好设置(JSON)")
    @TableField("preferences")
    private String preferences;

    @ApiModelProperty("用户等级")
    @TableField("level")
    private Integer level;

    @ApiModelProperty("经验值")
    @TableField("experience")
    private Integer experience;

    @ApiModelProperty("粉丝数")
    @TableField("follower_count")
    private Integer followerCount;

    @ApiModelProperty("关注数")
    @TableField("following_count")
    private Integer followingCount;

    @ApiModelProperty("点赞数")
    @TableField("like_count")
    private Integer likeCount;

    @ApiModelProperty("提交数")
    @TableField("submit_count")
    private Integer submitCount;

    @ApiModelProperty("通过数")
    @TableField("accepted_count")
    private Integer acceptedCount;

    @ApiModelProperty("积分")
    @TableField("rating")
    private Integer rating;

    @TableField("create_time")
    private LocalDateTime createTime;

    @ApiModelProperty("能力值分析缓存")
    @TableField("analysis")
    private String analysis;

    @TableField("update_time")
    private LocalDateTime updateTime;

    @ApiModelProperty("最后一次登录时间")
    @TableField("last_login_time")
    private LocalDateTime lastLoginTime;

    @TableField("is_deleted")
    private Integer isDeleted;
}
