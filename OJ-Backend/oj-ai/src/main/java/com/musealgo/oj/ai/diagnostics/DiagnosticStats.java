package com.musealgo.oj.ai.diagnostics;

import com.musealgo.oj.api.judge.RunUsageSink;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.util.*;

/** 有限维度的按日资源元数据，32 天自动清理；不保存代码/输入/输出正文。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DiagnosticStats implements RunUsageSink {
    public static final Set<String> METRICS = Set.of("run.manual","run.auto","run.completed","run.timeout","run.code-error","run.service-error","run.elapsed-ms","run.reused",
            "reference.hit","reference.miss","diagnosis.requests","diagnosis.model-rounds","diagnosis.verifications","diagnosis.counterexamples","diagnosis.unavailable");
    private static final DefaultRedisScript<Long> ADD = new DefaultRedisScript<>(
            "redis.call('HINCRBY',KEYS[1],ARGV[1],ARGV[2]);redis.call('EXPIRE',KEYS[1],2764800);"
            + "if ARGV[3] ~= '' then redis.call('ZINCRBY',KEYS[2],ARGV[2],ARGV[3]);redis.call('EXPIRE',KEYS[2],2764800);end;return 1",Long.class);
    private final StringRedisTemplate redis;
    @Override public void add(String metric, Long user, long amount) {
        if (!METRICS.contains(metric) || amount <= 0) return;
        try { String day=LocalDate.now().toString();redis.execute(ADD,List.of("ai:diagnostics:"+day,"ai:diagnostics:users:"+day),metric,Long.toString(amount),
                user != null && (metric.equals("run.manual") || metric.equals("run.auto")) ? user.toString() : ""); }
        catch (RuntimeException failure) { log.warn("诊断资源统计不可用：{}",failure.getClass().getSimpleName()); }
    }
    public Map<String,Object> overview(int days) {
        if(days<1||days>30)throw new com.musealgo.oj.common.exception.BusinessException(com.musealgo.oj.ai.error.AiErrorCode.AI_INPUT_INVALID,"统计天数为1～30");
        Map<String,Long> total=new TreeMap<>(); METRICS.forEach(key->total.put(key,0L));Map<String,Double> users=new HashMap<>();
        for(int i=0;i<days;i++){
            String day=LocalDate.now().minusDays(i).toString();
            redis.opsForHash().entries("ai:diagnostics:"+day).forEach((key,value)->{if(METRICS.contains(key.toString())) total.merge(key.toString(),Long.parseLong(value.toString()),Long::sum);});
            var ranks=redis.opsForZSet().rangeWithScores("ai:diagnostics:users:"+day,0,-1);
            if(ranks!=null)ranks.forEach(item->{if(item.getValue()!=null&&item.getScore()!=null)users.merge(item.getValue(),item.getScore(),Double::sum);});
        }
        var ranking=users.entrySet().stream().sorted(Map.Entry.<String,Double>comparingByValue().reversed()).limit(10)
                .map(entry->Map.of("userId",entry.getKey(),"runs",entry.getValue().longValue())).toList();
        return Map.of("days",days,"metrics",total,"ranking",ranking,"retentionDays",32);
    }
}
