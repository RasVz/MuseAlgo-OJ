package com.musealgo.oj.ai.review;

import com.musealgo.oj.ai.mapper.ReviewMapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.musealgo.oj.ai.AiService;
import com.musealgo.oj.ai.ReviewApplicationService;
import com.musealgo.oj.ai.dto.AiAskOutcome;
import com.musealgo.oj.ai.entity.AiUsage;
import com.musealgo.oj.ai.error.AiErrorCode;
import com.musealgo.oj.ai.gateway.AiStreamControl;
import com.musealgo.oj.ai.quota.AiQuotaService;
import com.musealgo.oj.ai.usage.AiUsageRecorder;
import com.musealgo.oj.common.exception.BusinessException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

/** 浏览器与执行器完全解耦；数据库原子领取，超时不自动重调模型。 */
@Component
public class ReviewWorker {
    private static final Logger LOG = LoggerFactory.getLogger(ReviewWorker.class);
    private final ReviewRecordService records;
    private final ReviewMapper mapper;
    private final ReviewApplicationService reviews;
    private final AiQuotaService quota;
    private final AiUsageRecorder usage;
    private final ThreadPoolExecutor executor;
    private final Set<Long> scheduled = ConcurrentHashMap.newKeySet();
    private final Map<Long,AiStreamControl> controls = new ConcurrentHashMap<>();
    private final boolean enabled;

    public ReviewWorker(ReviewRecordService records, ReviewMapper mapper, ReviewApplicationService reviews,
                        AiQuotaService quota, AiUsageRecorder usage,
                        @Value("${ai.review.worker.enabled:true}") boolean enabled,
                        @Value("${ai.review.worker.threads:2}") int threads) {
        this.records=records; this.mapper=mapper; this.reviews=reviews; this.quota=quota; this.usage=usage; this.enabled=enabled;
        int size=Math.max(1,Math.min(4,threads));
        this.executor=new ThreadPoolExecutor(size,size,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(32),r->{
            Thread t=new Thread(r,"ai-review-worker"); t.setDaemon(true); return t;
        },new ThreadPoolExecutor.AbortPolicy());
    }
    @EventListener
    public void created(ReviewRecordService.Created event) { if(enabled) submit(event.id()); }
    @EventListener
    public void stop(ReviewRecordService.StopRequested event) {
        AiStreamControl control=controls.get(event.id()); if(control!=null) control.cancel();
    }
    void submit(Long id) {
        if(!scheduled.add(id)) return;
        try { executor.execute(()->{try{run(id);}finally{scheduled.remove(id);}}); }
        catch(RejectedExecutionException busy) { scheduled.remove(id); LOG.debug("复盘队列已满，等待补派 id={}",id); }
    }
    void run(Long id) {
        String execution=UUID.randomUUID().toString();
        if(!records.claim(id,execution)) return;
        SubmissionReview record=mapper.full(id);
        StringBuilder partial=new StringBuilder();
        long[] checkpoint={0};
        boolean[] outputLimit={false};
        AiStreamControl control=new AiStreamControl(chunk->{
            if(partial.length()+chunk.length()>200_000) { outputLimit[0]=true; throw new AiStreamControl.Cancelled(); }
            partial.append(chunk);
            if(System.nanoTime()-checkpoint[0]>TimeUnit.SECONDS.toNanos(1)) {
                int changed=mapper.update(null, running(id,execution).set(SubmissionReview::getAnswer,partial.toString())
                        .set(SubmissionReview::getUpdateTime,LocalDateTime.now()));
                checkpoint[0]=System.nanoTime();
                if(changed==0) throw new AiStreamControl.Cancelled();
            }
        });
        controls.put(id,control);
        long start=System.nanoTime();
        AiAskOutcome outcome=null;
        String promptText="";
        String state="FAILED",error="AI_UPSTREAM_FAILED",notice="AI 生成未完成，请稍后重试";
        try {
            // 领取与注册取消回调之间可能刚被取消；复查后不发送模型请求。
            var latest=mapper.selectById(id);
            if(latest==null || !"RUNNING".equals(latest.getStatus())) throw new AiStreamControl.Cancelled();
            var request=records.teachingRequest(record);
            var material=records.material(record);
            String system=records.input(record).systemPrompt();
            promptText=com.musealgo.oj.ai.tutor.TutorMessageBuilder.flatten(reviews.conversation(request,material,system));
            outcome=reviews.stream(record.getUserId(),request,material,
                    Boolean.TRUE.equals(record.getPrivileged()),control,system);
            if(outputLimit[0]) {
                error="AI_OUTPUT_LIMIT"; notice="回复达到输出上限，当前内容未完成";
            } else if(AiUsage.STATUS_SUCCESS.equals(outcome.status())) {
                if(outcome.answer()==null || outcome.answer().isBlank()) throw new BusinessException(AiErrorCode.AI_RESPONSE_INVALID);
                state="SUCCEEDED"; error=null; notice=null;
            } else if(AiUsage.STATUS_CANCELLED.equals(outcome.status())) {
                state="CANCELLED"; error="AI_REVIEW_CANCELLED"; notice="已停止生成，已有内容不完整";
            } else if(AiUsage.STATUS_QUOTA_REJECTED.equals(outcome.status())) {
                error="AI_QUOTA_EXCEEDED"; notice=AiErrorCode.AI_QUOTA_EXCEEDED.message();
            } else if(AiUsage.STATUS_CONTEST_BLOCKED.equals(outcome.status())) {
                error="AI_CONTEST_BLOCKED"; notice=AiErrorCode.AI_CONTEST_BLOCKED.message();
            } else if("OUTPUT_LIMIT".equals(outcome.errorType())) {
                error="AI_OUTPUT_LIMIT"; notice="回复达到输出上限，当前内容未完成";
            } else if("TIMEOUT".equals(outcome.errorType())) {
                error="UPSTREAM_TIMEOUT"; notice="AI 服务响应超时，请稍后重试";
            }
        } catch(AiStreamControl.Cancelled cancelled) {
            state=outputLimit[0]?"FAILED":"CANCELLED"; error=outputLimit[0]?"AI_OUTPUT_LIMIT":"AI_REVIEW_CANCELLED"; notice=outputLimit[0]?"回复达到输出上限，当前内容未完成":"已停止生成，已有内容不完整";
        } catch(BusinessException failure) {
            error=failure.getErrorCode().key(); notice=failure.getMessage();
        } catch(Exception failure) {
            LOG.warn("复盘生成失败 id={} type={}",id,failure.getClass().getSimpleName());
        } finally {
            controls.remove(id,control);
            long latency=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
            try { finish(record,execution,state,error,notice,partial.toString(),promptText,outcome,latency); }
            catch(Exception storageFailure) { LOG.error("复盘终态写入失败，等待超时回收 id={}",id,storageFailure); }
        }
    }
    void finish(SubmissionReview record,String execution,String state,String error,String notice,String partial,String promptText,AiAskOutcome outcome,long latency) {
        boolean sent=outcome!=null ? outcome.requestSent() : !partial.isBlank();
        String prompt=outcome==null?promptText:outcome.promptText();
        int input=!sent?0:outcome==null || outcome.usage()==null?AiService.estimateTokens(prompt):outcome.usage().promptTokens();
        int output=!sent?0:outcome==null || outcome.usage()==null?AiService.estimateTokens(outcome==null?partial:outcome.answer()):outcome.usage().completionTokens();
        boolean estimated=outcome==null || outcome.usage()==null || outcome.usageEstimated();
        String answer="AI_OUTPUT_LIMIT".equals(error)?partial:outcome!=null && outcome.answer()!=null && !outcome.answer().isBlank()
                && !Set.of(AiUsage.STATUS_QUOTA_REJECTED,AiUsage.STATUS_CONTEST_BLOCKED).contains(outcome.status())?outcome.answer():partial;
        var update=running(record.getId(),execution).set(SubmissionReview::getStatus,state)
                .set(SubmissionReview::getErrorCode,error).set(SubmissionReview::getMessage,ReviewRecordService.clip(notice,512))
                .set(SubmissionReview::getAnswer,answer).set(SubmissionReview::getLatencyMs,latency)
                .set(SubmissionReview::getPromptTokens,input).set(SubmissionReview::getCompletionTokens,output)
                .set(SubmissionReview::getTokensEstimated,estimated).set(SubmissionReview::getFinishTime,LocalDateTime.now())
                .set(SubmissionReview::getUpdateTime,LocalDateTime.now());
        if(outcome!=null && outcome.source()!=null) {
            update.set(SubmissionReview::getModel,outcome.source().model()).set(SubmissionReview::getServiceName,outcome.source().serviceName())
                    .set(SubmissionReview::getSourceJson,records.write(outcome.source()));
        }
        boolean success=outcome!=null && AiUsage.STATUS_SUCCESS.equals(outcome.status());
        String usageState="AI_OUTPUT_LIMIT".equals(error) || outcome==null?AiUsage.STATUS_FAIL:outcome.status();
        try {
            int finalized=mapper.update(null,update);
            if(finalized==0) {
                // 取消请求先占住活动槽；只有执行器真正收尾才标为取消并释放。
                var stopped=new LambdaUpdateWrapper<SubmissionReview>().eq(SubmissionReview::getId,record.getId())
                        .eq(SubmissionReview::getExecutionId,execution).eq(SubmissionReview::getStatus,"CANCELLING")
                        .set(SubmissionReview::getStatus,"CANCELLED").set(SubmissionReview::getErrorCode,"AI_REVIEW_CANCELLED")
                        .set(SubmissionReview::getMessage,"已停止生成，已有内容不完整")
                        .set(SubmissionReview::getAnswer,answer).set(SubmissionReview::getLatencyMs,latency)
                        .set(SubmissionReview::getPromptTokens,input).set(SubmissionReview::getCompletionTokens,output)
                        .set(SubmissionReview::getTokensEstimated,estimated).set(SubmissionReview::getFinishTime,LocalDateTime.now());
                if(outcome!=null && outcome.source()!=null) stopped.set(SubmissionReview::getModel,outcome.source().model())
                        .set(SubmissionReview::getServiceName,outcome.source().serviceName()).set(SubmissionReview::getSourceJson,records.write(outcome.source()));
                boolean cancelled=mapper.update(null,stopped)==1;
                usageState=cancelled?AiUsage.STATUS_CANCELLED:AiUsage.STATUS_FAIL;
            }
        } finally {
            // 唯一执行令牌只会进入一次收尾；正文写库失败仍记录实际模型用量，不再请求模型。
            try { if(success) quota.consume(record.getUserId(),input+output,Boolean.TRUE.equals(record.getPrivileged())); }
            catch(Exception failed) { LOG.warn("复盘配额入账失败 id={}",record.getId()); }
            try { usage.record(record.getUserId(),record.getProblemId(),"REVIEW",input,output,estimated,latency,
                    usageState,"reviewId="+record.getId()+(error==null?"":"; "+error)+(outcome==null?"; 未获完整用量，上游费用未知":""),outcome==null?null:outcome.source()); }
            catch(Exception failed) { LOG.warn("复盘用量流水写入失败 id={}",record.getId()); }
        }
    }

    private static LambdaUpdateWrapper<SubmissionReview> running(Long id,String execution) {
        return new LambdaUpdateWrapper<SubmissionReview>().eq(SubmissionReview::getId,id)
                .eq(SubmissionReview::getExecutionId,execution).eq(SubmissionReview::getStatus,"RUNNING");
    }
    @Scheduled(fixedDelay=3000,initialDelay=3000)
    public void recover() {
        if(!enabled) return;
        try {
            mapper.update(null,new LambdaUpdateWrapper<SubmissionReview>().in(SubmissionReview::getStatus,"RUNNING","CANCELLING")
                    .lt(SubmissionReview::getStartTime,LocalDateTime.now().minusMinutes(6))
                    .set(SubmissionReview::getStatus,"FAILED").set(SubmissionReview::getErrorCode,"AI_REVIEW_INTERRUPTED")
                    .set(SubmissionReview::getMessage,"生成超时或服务中断，请手动重试")
                    .set(SubmissionReview::getFinishTime,LocalDateTime.now()).set(SubmissionReview::getUpdateTime,LocalDateTime.now()));
            controls.forEach((id,control)->{
                var record=mapper.selectById(id);
                if(record==null || !"RUNNING".equals(record.getStatus())) control.cancel();
            });
            mapper.selectList(new LambdaQueryWrapper<SubmissionReview>().select(SubmissionReview::getId)
                    .eq(SubmissionReview::getStatus,"QUEUED").orderByAsc(SubmissionReview::getId).last("LIMIT 32"))
                    .forEach(r->submit(r.getId()));
        } catch(Exception unavailable) { LOG.warn("复盘任务恢复暂不可用 type={}",unavailable.getClass().getSimpleName()); }
    }
    @PreDestroy
    public void close() { controls.values().forEach(AiStreamControl::cancel); executor.shutdownNow(); }
}
