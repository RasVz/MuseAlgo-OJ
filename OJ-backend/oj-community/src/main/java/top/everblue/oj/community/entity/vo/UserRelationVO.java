package top.everblue.oj.community.entity.vo;

import lombok.Data;

/**
 * 关注列表 / 粉丝列表的出参。
 * <p>
 * 与 {@link top.everblue.oj.community.entity.UserRelation} 的区别在于：
 * 本类是「用户档案 + 当前查看者与该用户的关注态」，
 * 而 {@code isFollowed} 必须按查看者逐行计算（一次批量查询全部关系再回填，
 * 不能在 SQL 里对每一行单独判断，否则退化成 N+1）。
 * </p>
 */
@Data
public class UserRelationVO {
    private Long id;          // 用户ID
    private String username;
    private String nickname;
    private String avatar;
    private String signature; // 个性签名

    // 核心字段：当前查看者是否关注了这个人
    private Boolean isFollowed;
}