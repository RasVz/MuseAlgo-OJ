package top.everblue.oj.system.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import top.everblue.oj.api.user.UserDisplayInfo;
import top.everblue.oj.api.user.UserQuery;
import top.everblue.oj.system.entity.User;
import top.everblue.oj.system.mapper.UserMapper;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 用户只读查询适配器：实现 oj-api 定义的 {@link UserQuery}，供其他模块跨模块读取用户信息。
 * <p>
 * 项目约定：跨模块读取一律通过 oj-api 的接口契约，接口在 oj-api 定义、实现放在数据归属的 oj-system，
 * 这样 oj-api 不反向依赖 oj-system，各业务模块也不互相直接注入 Mapper。
 * <strong>新增跨模块查询请沿用这个模式</strong>，而不是在调用方直接注入 {@link UserMapper}。
 * </p>
 * <p>
 * <strong>出参只有 {@link UserDisplayInfo} 这一种精简结构</strong>，且刻意不含 email、role、lastLoginTime：
 * 排行榜、提交列表、主页卡片这些「顺带展示用户名」的场景全走这里，
 * 一次疏忽就能把邮箱和管理员身份带到不该出现的地方。新增字段前先确认所有调用方真的需要它。
 * </p>
 * <p>
 * 隐私相关方法的<strong>默认值是「公开」</strong>：{@code shareSubmissions} 缺失（老数据没有该
 * preferences 项）时视为已开启分享，而不是默认隐藏——反过来会让升级前的老用户突然「消失」，
 * 且原因难以排查。同理 {@code userId == null} 时按开启处理，避免边界情况误伤。
 * </p>
 * <p>
 * 注意 {@code apply} 里是原始 SQL 片段，不参与参数绑定；这里只拼固定的常量条件，
 * 没有注入风险，<strong>不要改成拼接用户输入的形式</strong>。
 * 另外 preferences 是 JSON 列，用 {@code JSON_EXTRACT} 查询无法走普通索引，
 * 因此 {@link #findUserIdsWithSubmissionsHidden()} 在用户量大时会是慢查询——
 * 它目前只用于提交列表过滤，如成为瓶颈应改为冗余一个布尔列。
 * </p>
 */
@Service
@RequiredArgsConstructor
public class UserQueryAdapter implements UserQuery {

    private final UserMapper userMapper;

    @Override
    public Optional<UserDisplayInfo> findDisplayInfo(Long userId) {
        if (userId == null) return Optional.empty();
        User user = userMapper.selectById(userId);
        return Optional.ofNullable(user).map(this::toDisplayInfo);
    }

    @Override
    public Map<Long, UserDisplayInfo> findDisplayInfoBatch(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) return Collections.emptyMap();
        List<User> users = userMapper.selectBatchIds(userIds);
        return users.stream()
                .collect(Collectors.toMap(User::getId, this::toDisplayInfo));
    }

    @Override
    public Optional<Long> findIdByUsername(String username) {
        if (username == null || username.isBlank()) return Optional.empty();
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        return Optional.ofNullable(user).map(User::getId);
    }

    @Override
    public List<Long> findUserIdsWithSubmissionsHidden() {
        // preferences JSON 中 shareSubmissions=false 视为关闭分享；缺失时默认分享
        return userMapper.selectList(new LambdaQueryWrapper<User>()
                        .isNotNull(User::getPreferences)
                        .apply("JSON_UNQUOTE(JSON_EXTRACT(preferences, '$.shareSubmissions')) = 'false'"))
                .stream()
                .map(User::getId)
                .toList();
    }

    @Override
    public boolean isSubmissionSharingEnabled(Long userId) {
        if (userId == null) return true;
        Long hidden = userMapper.selectCount(new LambdaQueryWrapper<User>()
                .eq(User::getId, userId)
                .isNotNull(User::getPreferences)
                .apply("JSON_UNQUOTE(JSON_EXTRACT(preferences, '$.shareSubmissions')) = 'false'"));
        return hidden == null || hidden == 0;
    }

    private UserDisplayInfo toDisplayInfo(User u) {
        return new UserDisplayInfo(
                u.getId(), u.getUsername(), u.getNickname(),
                u.getAvatar(), u.getMotto(), u.getSchool(),
                u.getAcceptedCount()
        );
    }
}
