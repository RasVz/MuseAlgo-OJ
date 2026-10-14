package top.everblue.oj.community.entity.vo;

import lombok.Data;
import lombok.EqualsAndHashCode;
import top.everblue.oj.community.entity.Post;

/**
 * 动态列表 / 详情的出参。
 * <p>
 * 通过继承 {@link top.everblue.oj.community.entity.Post} 复用字段，而不是组合一个
 * {@code post} 属性：前端拿到的就是「动态字段 + 附加字段」的平铺结构，
 * 换成组合会逼着前端改取值路径，而实体字段一多，重复声明的维护成本高于继承的耦合代价。
 * 代价是 VO 与实体表结构绑定——{@code post} 加字段会直接透出到接口，
 * 因此这里只承载<b>已经确定对外</b>的字段，未定稿的实体字段不自动继承。
 * </p>
 * <p>
 * {@code contestId} / {@code problemId} / {@code classId} / {@code images} 在父类中已存在，
 * 此处重复声明是为了固定 JSON 输出顺序，不参与业务逻辑，不要误删。
 * </p>
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class PostVO extends Post {
    private String motto;
    private String authorName;
    private String authorAvatar;
    private Boolean isFollowed;
    private Long contestId;
    private Long problemId;
    private Long classId;
    private String images;
    private Boolean isLiked;
    private java.util.List<RelatedProblemVO> relatedProblems;
}
