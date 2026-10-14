package top.everblue.oj.contest.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import top.everblue.oj.api.submission.ContestSubmission;
import top.everblue.oj.api.submission.ContestSubmissionQuery;
import top.everblue.oj.api.submission.SubmissionJudgedEvent;
import top.everblue.oj.contest.service.RankRedisService;
import top.everblue.oj.contest.sse.SseRegistry;
import top.everblue.oj.contest.entity.ContestRank;
import top.everblue.oj.contest.mapper.ContestRankMapper;

/**
 * 比赛排名消费者 —— 监听 submission.judged 事件，更新 Redis 实时排名。
 *
 * <h3>消费流程</h3>
 * <ol>
 *   <li>接收 Outbox 发布器投递的 {@code submission.judged} 消息</li>
 *   <li>反序列化为 {@link SubmissionJudgedEvent}（编译期类型安全）</li>
 *   <li>通过 {@link ContestSubmissionQuery#findById} 获取只读视图</li>
 *   <li>若 contestId 为 null → 非比赛提交，跳过（ACK）</li>
 *   <li>调用 {@link RankRedisService#submitProblem} 更新排名</li>
 * </ol>
 *
 * <h3>解耦设计</h3>
 * <p>
 * 本消费者不直接注入任何 Mapper，全部通过 {@link ContestSubmissionQuery}（oj-api 契约）
 * 获取 submission 数据。队列名定义为本类常量，不依赖 oj-judge 的拓扑配置类。
 * </p>
 *
 * <h3>幂等保障</h3>
 * <p>
 * {@link RankRedisService#submitProblem} 内部会检查问题是否已解决（solved flag），
 * 重复消费同一 submission 不会重复计分。
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "oj.messaging", name = "rabbitmq-enabled", havingValue = "true")
public class ContestRankConsumer {

    // 下面这个字面量刻意不 import oj-judge 侧拓扑配置类的 public static final 常量：
    // 引用对方常量会把 oj-contest 与 oj-judge 编译期绑死（对方改名即本模块编译失败），
    // 且形成了不该有的模块依赖方向。本模块只作为消费方，用字面量声明自己「听哪个队列」，
    // 让生产方可以独立演进；代价是两处字面量需人工保持一致，改动时两侧同步改。
    /** 与 RabbitJudgeTopologyConfiguration.RANK_QUEUE 保持一致 */
    static final String RANK_QUEUE = "oj.rank.update";

    private final ContestSubmissionQuery submissionQuery;
    private final RankRedisService rankRedisService;
    private final SseRegistry sseRegistry;
    private final ContestRankMapper contestRankMapper;

    /**
     * 独立于 Spring 容器持有的 {@link ObjectMapper}。
     * <p>
     * 刻意不用容器里那个：消息体字段演进时，全局 ObjectMapper 上的
     * {@code FAIL_ON_UNKNOWN_PROPERTIES} 等宽松开关属于 Web 层调优策略，
     * 一旦被别处改成严格模式，MQ 消息就会因为多一个字段而全部解析失败。
     * MQ 反序列化需要自己掌控的严格性边界。
     * </p>
     */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /**
     * 监听 oj.rank.update 队列，处理 submission.judged 事件。
     * <p>
     * <b>为什么本地事务与消息发送要解耦：</b>判题结果落库与「通知榜单刷新」是两件事，
     * 若放在同一个数据库事务里同步发消息，RabbitMQ 不可用时会把整个判题写入回滚；
     * 反之先发消息后落库，则消息可能指向一条尚未提交的 submission。改由 Outbox 模式：
     * 判题结果与一条待投递记录在<b>同一个本地事务</b>内写入，后台轮询器再把待投递记录发到 MQ。
     * 这样「消息一定不早于数据提交」，且 MQ 抖动不再影响主流程，代价是引入最多一个轮询周期的延迟。
     * </p>
     *
     * <p>
     * <b>异常与重试策略分两类：</b>
     * <ul>
     *   <li>解析失败 / submission 不存在 / 非比赛提交 / VP 提交 → {@code return} 直接 ACK 丢弃。
     *       这些是<b>不可重试</b>的终态：重放同一条消息结果完全一样，只会让队列堆积并触发无限重投递。</li>
     *   <li>其余异常 → 抛出 {@link RuntimeException} 让容器按退避策略重试。
     *       这类是瞬时故障（DB 抖动、Redis 超时），重投有机会成功；这里<b>没有做去重消费</b>，
     *       正确性由 {@link RankRedisService#submitProblem} 自身的幂等性兜底。</li>
     * </ul>
     * </p>
     *
     * <p>
     * <b>为什么 SSE 推送要单独 try-catch：</b>推送失败属于「展示层降级」，此时 Redis 排名已经更新成功，
     * 若让它冒泡就会触发整条消息重投，把一次无害的推送故障升级成重复的榜单写入。
     * 玩家刷新页面仍能从数据库拿到正确榜单。
     * </p>
     *
     * @param payload JSON 格式的 {@link SubmissionJudgedEvent}
     */
    @RabbitListener(queues = RANK_QUEUE, concurrency = "1-3")
    public void onSubmissionJudged(String payload) {
        // 1) 反序列化消息（类型安全，替代原始 Map 解析）
        SubmissionJudgedEvent event;
        try {
            event = OBJECT_MAPPER.readValue(payload, SubmissionJudgedEvent.class);
        } catch (Exception e) {
            log.error("【排名消费者】消息解析失败 payload={}: {}", payload, e.getMessage());
            return; // 无效消息，ACK 丢弃（不重试）
        }

        Long submissionId = event.submissionId();

        try {
            // 2) 通过 oj-api 契约查询（不直接注入 SubmissionMapper）
            ContestSubmission submission = submissionQuery.findById(submissionId)
                    .orElse(null);

            if (submission == null) {
                log.warn("【排名消费者】submissionId={} 不存在，跳过", submissionId);
                return;
            }

            // 非比赛提交 → 静默跳过
            if (submission.contestId() == null) {
                log.debug("【排名消费者】submissionId={} 非比赛提交，跳过", submissionId);
                return;
            }
            // VP（Virtual Participation，虚构参赛）提交只写 VP 榜单，不进正式榜：
            // VP 是给未报名选手体验用的，若混入正式榜会凭空挤掉真实选手的名次
            if (submission.vpAttemptId() != null) {
                log.debug("【排名消费者】submissionId={} 为 VP 提交，跳过正式榜", submissionId);
                return;
            }

            // 3) 更新 Redis 排名（内部幂等：重复投递同一 submissionId 不会重复计分，
            //    幂等键就是 Redis 里该 (contestId,userId,problemId) 的 solved 标记与 OI 模式的 score 比较）
            rankRedisService.submitProblem(
                    submission.contestId(),
                    submission.userId(),
                    submission.problemId(),
                    ContestSubmission.statusLabel(submission.status()),
                    submission.score() == null ? 0 : submission.score(),
                    submission.submittedAt()
            );

            log.info("【排名消费者】排名已更新 contestId={}, userId={}, submissionId={}",
                    submission.contestId(), submission.userId(), submissionId);

            // SSE 推送最新排名：广播的是快照而非增量，前端整表替换，
            // 因此乱序到达的旧快照最多造成一次画面回退，不会出现条目错乱
            try {
                var ranking = contestRankMapper.selectList(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ContestRank>()
                        .eq(ContestRank::getContestId, submission.contestId())
                        .orderByAsc(ContestRank::getRankNum));
                if (ranking != null && !ranking.isEmpty()) {
                    sseRegistry.broadcast(submission.contestId(), ranking);
                }
            } catch (Exception e) {
                log.warn("SSE 推送失败 contestId={}: {}", submission.contestId(), e.getMessage());
            }

        } catch (Exception e) {
            log.error("【排名消费者】处理失败 submissionId={}: {}", submissionId, e.getMessage());
            throw new RuntimeException("Failed to process submission.judged for " + submissionId, e);
        }
    }
}
