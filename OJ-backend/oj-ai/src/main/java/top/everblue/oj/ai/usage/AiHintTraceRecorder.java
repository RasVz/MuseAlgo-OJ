package top.everblue.oj.ai.usage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import top.everblue.oj.ai.entity.AiHintTrace;
import top.everblue.oj.ai.mapper.AiHintTraceMapper;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;

/**
 * Hint Ladder 解锁轨迹异步记录器。
 * <p>
 * 与 {@link AiUsageRecorder} 同模式：写库走独立的单线程 executor，
 * 绝不阻塞解锁主链路（Redis 写入才是解锁的关键路径）；
 * 落库失败只记 WARN——轨迹是学情数据，不能反过来影响答疑可用性。
 * </p>
 */
@Slf4j
@Service
public class AiHintTraceRecorder {

    private final AiHintTraceMapper hintTraceMapper;
    private final ExecutorService aiUsageExecutor;

    public AiHintTraceRecorder(AiHintTraceMapper hintTraceMapper,
                               @Qualifier("aiUsageExecutor") ExecutorService aiUsageExecutor) {
        this.hintTraceMapper = hintTraceMapper;
        this.aiUsageExecutor = aiUsageExecutor;
    }

    /**
     * 异步落一条解锁轨迹。
     *
     * @param source 解锁来源（USER / TEACHER / ADMIN），见 {@link AiHintTrace}
     */
    public void record(Long userId, Long problemId, int level, String source) {
        aiUsageExecutor.execute(() -> {
            try {
                AiHintTrace trace = new AiHintTrace();
                trace.setUserId(userId);
                trace.setProblemId(problemId);
                trace.setLevel(level);
                trace.setSource(source == null ? AiHintTrace.SOURCE_USER : source);
                trace.setCreatedAt(LocalDateTime.now());
                hintTraceMapper.insert(trace);
            } catch (Exception e) {
                log.warn("【HintLadder】解锁轨迹落库失败 userId={}, problemId={}, level={}, err={}",
                        userId, problemId, level, e.getMessage());
            }
        });
    }
}
