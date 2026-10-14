package top.everblue.oj.community.service;

import top.everblue.oj.community.entity.Post;
import com.baomidou.mybatisplus.spring.service.IService;

/**
 * 动态的数据访问基座接口，只提供 MyBatis-Plus 的通用 CRUD。
 * <p>
 * <b>这里刻意是空接口：</b>动态的发布/编辑、查询、互动各有专门服务
 * （{@link top.everblue.oj.community.content.PostContentService}、
 * {@link top.everblue.oj.community.query.PostQueryService}、
 * {@link top.everblue.oj.community.interaction.PostInteractionFacade}），
 * 它们把本接口当作底层查询工具使用。
 * 若在此添加发布、删除等业务方法，等于开出第二条写动态的路径，
 * 运营标志位与类型推断规则只会在其中一处生效。
 * </p>
 */
public interface PostService extends IService<Post> {
}
