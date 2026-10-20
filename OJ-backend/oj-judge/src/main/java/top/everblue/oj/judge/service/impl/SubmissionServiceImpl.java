package top.everblue.oj.judge.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.everblue.oj.api.problem.ProblemQuery;
import top.everblue.oj.judge.entity.Submission;
import top.everblue.oj.judge.entity.SubmissionCode;
import top.everblue.oj.judge.messaging.JudgeTaskNotifier;
import top.everblue.oj.judge.mapper.SubmissionCodeMapper;
import top.everblue.oj.judge.mapper.SubmissionMapper;
import top.everblue.oj.judge.domain.SubmissionStatus;
import top.everblue.oj.judge.service.SubmissionService;
import top.everblue.oj.judge.service.JudgeTaskService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 提交服务实现：负责「落库 → 入队 → 唤醒判题 Worker」这条主链路。
 * <p>
 * 提交<strong>先落库再入队</strong>，顺序不能反：反过来的话，Worker 可能在提交记录写好之前就来取任务，
 * 查不到任务只能等下一轮轮询，用户会看到「提交后长时间无响应」。同理，入队成功后立刻返回，
 * 判题是异步的，前端靠轮询提交状态获取结果。
 * </p>
 * <p>
 * <strong>判题前置校验用例非空</strong>：没有测试用例的题目即便判题成功也无法给出正确结论，
 * 早失败（明确报错）比晚失败（一直 PENDING）好排查得多。
 * </p>
 * <p>
 * MQ 唤醒用 {@link ObjectProvider} 软依赖：{@code oj.messaging.rabbitmq-enabled=false} 时该 Bean 不存在，
 * 代码自动退化为纯 DB 轮询模式而不是启动失败。这让「不部署 MQ 也能跑起来」成为默认配置。
 * 唤醒失败同样不回滚提交——DB 轮询是兜底路径，丢一次唤醒只影响响应速度，不影响正确性。
 * </p>
 * <p>
 * 源码写入独立的 {@code submission_code} 表（V50 拆表）：主表不再含 code 大字段，
 * 列表面页查询因此不必连带读取整段代码，判题回写结果时也不再重写源码。
 * 这条约束对判题回写逻辑同样成立——改回写时不要顺手把 code 塞回主表。
 * </p>
 */
@Service
@RequiredArgsConstructor
public class SubmissionServiceImpl extends ServiceImpl<SubmissionMapper, Submission> implements SubmissionService {

    private final ProblemQuery problemQuery;
    private final JudgeTaskService judgeTaskService;
    /** RabbitMQ 唤醒器，仅 oj.messaging.rabbitmq-enabled=true 时存在；DB 轮询是兜底 */
    private final ObjectProvider<JudgeTaskNotifier> judgeTaskNotifier;
    /** V50 拆表：源码独立存储（1:1 懒加载） */
    private final SubmissionCodeMapper submissionCodeMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Submission submitCode(Submission submission) {
        // 查测试用例（走 oj-api 只读契约，不直访 oj-problem Mapper）
        if (problemQuery.findTestCases(submission.getProblemId()).isEmpty()) {
            throw new IllegalStateException("题目没有可用测试用例，无法评测");
        }

        submission.setStatus(SubmissionStatus.PENDING.code());
        submission.setJudgePhase("QUEUED");
        // submission.create_time 为 DATETIME(0)，显式取整秒，避免数据库舍入将接收时间推到未来。
        submission.setCreateTime(LocalDateTime.now().withNano(0));
        submission.setRunTime(0);
        submission.setRunMemory(0);
        submission.setScore(0);
        this.save(submission);

        // V50：源码入 submission_code（主表不含 code，判题回写不再重写源码）
        if (submission.getCode() != null) {
            SubmissionCode codeRecord = new SubmissionCode();
            codeRecord.setSubmissionId(submission.getId());
            codeRecord.setCode(submission.getCode());
            submissionCodeMapper.insert(codeRecord);
        }

        judgeTaskService.submit(submission.getId());
        // MQ 唤醒 Worker（毫秒级响应）；失败时 DB 轮询兜底，不影响提交结果
        judgeTaskNotifier.ifAvailable(notifier -> notifier.notifyNewTask(submission.getId()));
        return submission;
    }

    @Override
    public List<Map<String, Object>> getUserDailySubmissionStats(Long userId, LocalDateTime startTime, LocalDateTime endTime) {
        return baseMapper.selectUserDailySubmissionStats(userId, startTime, endTime);
    }
}
