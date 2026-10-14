package top.everblue.oj.teaching.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 作业管理接口的占位类。
 * <p>
 * 作业的核心能力目前<strong>不在 HTTP 层</strong>：作业布置/批改由教学服务与后台页面承载，
 * 学生侧的提交情况通过 {@code HomeworkSubmissionQuery}（由 oj-judge 实现）以查询方式提供，
 * 而不是在这个控制器下开接口。
 * </p>
 * <p>
 * 因此本类<strong>当前不含任何接口</strong>，但它已经声明了 {@code @RestController} 与
 * {@code /homework} 路径前缀——一旦加入第一个方法，该前缀下的所有路径都会需要经过
 * Spring Security 白名单评估，<strong>新增接口时请同步确认 SecurityConfig 中的放行规则</strong>，
 * 作业数据是敏感信息，默认不应匿名可访问。
 * </p>
 * <p>
 * 类名带 {@code Class} 前缀而路径是 {@code /homework}，这个不一致是既有的：
 * 路径前缀面向前端契约，改名会造成破坏性变更，因此保持现状；
 * 但如果新增「班级作业」相关接口，请另建控制器而不是往这里堆，以免语义继续错位。
 * </p>
 */
@Tag(name = "作业管理", description = "作业提交与成绩查看")
@RestController
@RequestMapping("/homework")
public class ClassHomeworkController {
}
