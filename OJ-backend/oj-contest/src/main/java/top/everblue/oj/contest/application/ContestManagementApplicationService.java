package top.everblue.oj.contest.application;

import org.springframework.stereotype.Service;
import top.everblue.oj.common.utils.SecurityUtils;
import top.everblue.oj.contest.entity.Contest;
import top.everblue.oj.contest.service.ContestService;

import java.time.Duration;

/**
 * Owns contest write rules so generic persistence methods do not escape to HTTP.
 * <p>
 * 比赛写操作的规则归属层：拥有比赛「创建 / 更新 / 删除」的完整业务规则，
 * 使通用的持久化方法不会直接暴露给 HTTP 层。
 * <p>
 * <b>为什么要单独一层：</b>{@code ContestService} 继承自 {@code IService}，
 * 其 {@code save/updateById/removeById} 是<b>任何人任何地方都能直接调用</b>的通用方法，
 * 没有任何校验。若 Controller 直接调用它们，鉴权、时间合法性、字段白名单就全靠 Controller 自己记得写——
 * 只要有人图省事新写一个端点直接调 {@code contestService.save(...)}，
 * 就能凭空造出一场没有作者、赛程颠倒的比赛。
 * 把规则收拢到本类后，Controller 只能调用这三个入口，而这三个入口自带全部校验。
 * </p>
 *
 * <h3>三个入口共同的三层校验</h3>
 * <ol>
 *   <li><b>角色校验</b>：{@code requireTeacherOrAdmin}，仅教师与管理员可写。</li>
 *   <li><b>归属校验</b>：{@code requireOwnerOrAdmin}，教师只能改自己创建的比赛，管理员不限。
 *       顺序不可颠倒——必须先确认角色、取出作者，再校验「是本人或管理员」。</li>
 *   <li><b>字段白名单</b>：{@link #applyEditableFields} 逐字段赋值，
 *       而不是把请求对象整体拷贝过去。这保证 {@code authorId}（作者）、{@code id}（主键）、
 *       {@code participantCount}（报名人数，实时累加）、{@code plagiarismChecked}（查重标记）
 *       这些<b>不能由请求方决定</b>的字段不会被前端覆盖。</li>
 * </ol>
 *
 * <h3>关于 {@code duration}</h3>
 * <p>
 * {@code duration} 不接受前端传值，每次都由 {@code startTime} 与 {@code endTime} 现算。
 * 它是赛程的<b>派生值</b>：若允许独立传入，就会出现「时长与起止时间对不上」的数据，
 * 而榜单的罚时计算依赖它。要改时长就改时间，二者永远保持自洽。
 * </p>
 */
@Service
public class ContestManagementApplicationService {

    private final ContestService contestService;

    public ContestManagementApplicationService(ContestService contestService) {
        this.contestService = contestService;
    }

    /**
     * 创建比赛。
     *
     * @param command 前端提交的数据；其中的 {@code authorId}、{@code duration} 会被忽略并由服务端决定
     * @param actorId 操作者 ID，将被写为比赛作者
     * @return 已落库的比赛实体（含自增主键）
     * @throws ForbiddenException 调用者非教师/管理员时抛出
     * @throws IllegalArgumentException 赛程时间不合法时抛出
     * @throws IllegalStateException 落库失败时抛出
     */
    public Contest create(Contest command, Long actorId) {
        SecurityUtils.requireTeacherOrAdmin();
        validateTime(command);
        Contest contest = new Contest();
        applyEditableFields(contest, command);
        String mode=command.getMode()==null ? "ACM" : command.getMode().trim().toUpperCase(java.util.Locale.ROOT);
        if(!java.util.Set.of("ACM","OI").contains(mode)) throw new IllegalArgumentException("比赛模式仅支持ACM或OI");
        contest.setMode(mode);
        contest.setAuthorId(actorId);
        contest.setDuration(durationMinutes(contest));
        if (!contestService.save(contest)) {
            throw new IllegalStateException("创建比赛失败");
        }
        return contest;
    }

    /**
     * 更新比赛。仅作者本人或管理员可改。
     *
     * @param command 待更新数据，必须携带 id
     * @throws IllegalArgumentException id 为空或赛程时间不合法时抛出
     * @throws IllegalStateException 比赛不存在、越权或落库失败时抛出
     */
    @org.springframework.transaction.annotation.Transactional(rollbackFor=Exception.class)
    public void update(Contest command) {
        if (command.getId() == null) {
            throw new IllegalArgumentException("ID不能为空");
        }
        Contest existing = requireManagedContest(command.getId());
        validateTime(command);
        if(command.getMode()!=null && !command.getMode().equalsIgnoreCase(existing.getMode()))
            throw new IllegalStateException("已创建比赛不能更改赛制");
        if(existing.getRankFinalizedAt()!=null && (!java.util.Objects.equals(existing.getStartTime(),command.getStartTime())
                || !java.util.Objects.equals(existing.getEndTime(),command.getEndTime())))
            throw new IllegalStateException("已固化比赛不能更改赛程");
        applyEditableFields(existing, command);
        existing.setDuration(durationMinutes(existing));
        if (!contestService.updateById(existing)) {
            throw new IllegalStateException("更新比赛失败");
        }
    }

    /**
     * 删除比赛（逻辑删除，报名记录与成绩保留）。
     *
     * @param contestId 比赛 ID
     * @throws IllegalStateException 比赛不存在、越权或删除失败时抛出
     */
    @org.springframework.transaction.annotation.Transactional(rollbackFor=Exception.class)
    public void delete(Long contestId) {
        requireManagedContest(contestId);
        if (!contestService.removeById(contestId)) {
            throw new IllegalStateException("删除比赛失败");
        }
    }

    /**
     * 取出比赛并完成「角色 + 归属」双重校验，是更新与删除的共同前置。
     * <p>
     * <b>校验顺序有意义：</b>先 {@code requireTeacherOrAdmin} 再查库，
     * 非教师根本不会触发一次数据库查询；{@code requireOwnerOrAdmin} 必须在查出 authorId 之后才能做。
     * </p>
     *
     * @return 存在的比赛实体
     * @throws IllegalArgumentException 比赛不存在时抛出
     * @throws ForbiddenException 角色不符或非本人非管理员时抛出
     */
    private Contest requireManagedContest(Long contestId) {
        SecurityUtils.requireTeacherOrAdmin();
        Contest contest = contestService.getByIdForUpdate(contestId);
        if (contest == null) {
            throw new IllegalArgumentException("比赛不存在");
        }
        SecurityUtils.requireOwnerOrAdmin(contest.getAuthorId());
        return contest;
    }

    /**
     * 校验赛程时间必须成对存在且结束不早于开始。
     * <p>
     * 用 {@code isBefore}（而非 {@code isAfter}）判断，
     * 意味着<b>起止时刻相同是允许的</b>——零时长比赛虽不常用但语义合法，不该在这里被误拒。
     * </p>
     *
     * @throws IllegalArgumentException 时间缺失或结束早于开始时抛出
     */
    private void validateTime(Contest contest) {
        if (contest == null || contest.getStartTime() == null || contest.getEndTime() == null
                || contest.getEndTime().isBefore(contest.getStartTime())) {
            throw new IllegalArgumentException("比赛开始时间和结束时间不合法");
        }
    }

    /**
     * 由起止时间现算时长（分钟）。调用前必须已通过 {@link #validateTime}。
     */
    private int durationMinutes(Contest contest) {
        return (int) Duration.between(contest.getStartTime(), contest.getEndTime()).toMinutes();
    }

    /**
     * 逐字段拷贝<b>可编辑</b>字段，构成字段白名单。
     * <p>
     * <b>这里必须逐字段赋值，不能用属性拷贝工具整体搬过去：</b>
     * 整个实体的其余字段都是「不该由请求方决定」的——{@code id}、{@code authorId}、
     * {@code participantCount}、{@code createTime/updateTime}、{@code plagiarismChecked}，
     * 还有实体的 {@code registered}（非数据库字段，仅供展示）。
     * 整体拷贝等于把这些字段全部开放给前端，改动规模越小、漏洞越大。
     * </p>
     * <p>
     * <b>注意 {@code mode} 也不在白名单内</b>：比赛模式决定计分规则与榜单可见性，
     * 在赛程已发布、选手已按某一模式提交之后再改它会让历史成绩失去意义。
     * 新建比赛时应由开设流程另行指定，而非通过「编辑比赛」这个通用表单修改。
     * </p>
     *
     * @param target 待写入的目标实体
     * @param source 前端提交的数据
     */
    private void applyEditableFields(Contest target, Contest source) {
        target.setTitle(source.getTitle());
        target.setDescription(source.getDescription());
        target.setStartTime(source.getStartTime());
        target.setEndTime(source.getEndTime());
        target.setType(source.getType());
        target.setVisible(source.getVisible());
        target.setClassId(source.getClassId());
    }
}
