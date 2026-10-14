package top.everblue.oj.community.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.everblue.oj.community.entity.Post;
import top.everblue.oj.community.mapper.PostMapper;
import top.everblue.oj.community.service.PostService;

/**
 * 动态的 MyBatis-Plus 基座实现，仅提供 {@code IService} 的通用 CRUD。
 * <p>
 * <b>动态的业务规则刻意不写在这里：</b>
 * 发布与编辑在 {@link top.everblue.oj.community.content.PostContentServiceImpl}，
 * 查询在 {@link top.everblue.oj.community.query.PostQueryServiceImpl}，
 * 互动在 {@link top.everblue.oj.community.interaction.PostInteractionHandler}。
 * 本类被它们当作「按条件查、按 ID 取」的数据访问工具使用，
 * 一旦在这里加上发布/编辑逻辑，就会出现两条写动态的路径，
 * 运营标志位、类型推断这类规则必然只在一处生效。
 * </p>
 * <p>
 * 本类中的 {@link com.baomidou.mybatisplus.extension.service.impl.ServiceImpl}
 * 使用全限定名书写，而同包其他 {@code *ServiceImpl} 都做了 import。
 * 这不是笔误，是本文件先于其他文件存在、改写时保留了原样；
 * 两种写法在同一包内并存不影响功能。若要统一风格，
 * 建议改本文件（改为 import），而非反向改动其余六个文件。
 * </p>
 */
@Service
@RequiredArgsConstructor
public class PostServiceImpl extends com.baomidou.mybatisplus.extension.service.impl.ServiceImpl<PostMapper, Post>
        implements PostService {

}
