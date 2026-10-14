package top.everblue.oj.system.entity.vo;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 用户信息出参，用于主页与各类用户卡片的对外展示。
 * <p>
 * <strong>这个类是敏感字段的最终防线。</strong> 它与 {@code User} 实体字段名大量重合，
 * 调用方习惯用 {@code BeanUtils.copyProperties(user, vo)} 一把拷贝——这意味着
 * 只要往本类<strong>新增一个与实体同名的字段</strong>，对应值就会被自动带出去。
 * 因此新增字段前必须回答：它是否含密码哈希、手机号、邮箱验证状态、内部备注这类不该公开的信息？
 * </p>
 * <p>
 * 已有字段的公开性是刻意分级的：{@code email} 属于半公开（主页展示时通常前端会隐藏），
 * {@code role} 暴露会泄露管理员身份，因此任何返回本 VO 的接口都应确认前端确实需要该字段。
 * <strong>本类刻意不含 password 及其哈希</strong>——即便某些内部接口直接返回 User 实体，
 * 只要经由本 VO 出参就不会带上凭据。新增出参类型时请照此办理。
 * </p>
 * <p>
 * 统计类字段（followerCount / submitCount / rating）来自 user 表上的缓存列而非实时聚合，
 * 与主页接口的取数策略一致：有延迟，换取个人主页这个高频页面的数据库压力可控。
 * {@code isFollowed} 是<strong>相对当前登录用户</strong>的相对量，不是用户自身的属性，
 * 未登录时应为 false 而非 null——前端据此决定是否显示关注按钮。
 * </p>
 */
@Data
public class UserVO {
    private Long id;
    private String username;
    private String nickname;
    private Byte role;
    private String email;
    private String avatar;
    private String school;

    private String major;
    private String motto;
    private String blog;
    private String github;
    private Integer submitCount;
    private Integer acceptedCount;
    private Integer rating;
    private LocalDateTime createTime;

    // 社交统计信息
    private Long followerCount;  // 粉丝数
    private Long followingCount; // 关注数
    private Long likeCount;      // 获赞数
    private Boolean isFollowed;  // 我是否关注了他
}