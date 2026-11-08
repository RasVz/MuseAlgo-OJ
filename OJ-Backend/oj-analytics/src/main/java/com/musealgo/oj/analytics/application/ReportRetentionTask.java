package com.musealgo.oj.analytics.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import com.musealgo.oj.analytics.entity.ReportJobAttempt;
import com.musealgo.oj.analytics.mapper.ReportJobAttemptMapper;
import com.musealgo.oj.analytics.entity.ReportDelivery;
import com.musealgo.oj.analytics.entity.ReportJob;
import com.musealgo.oj.analytics.mapper.ReportDeliveryMapper;
import com.musealgo.oj.analytics.mapper.ReportJobMapper;
import com.musealgo.oj.api.report.ReportNotificationPort;

import java.util.List;
import java.util.HashSet;
import java.util.Set;

/**
 * T9-2 报告留存清理：默认保留 12 个月，可通过配置关闭或调整。
 * <p>报告含姓名/成绩/薄弱点，过期后删除 job 与 delivery；按批次记录删除数量与截止日期，不记录报告正文。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportRetentionTask {

    private final ReportJobMapper jobMapper;
    private final ReportJobAttemptMapper attemptMapper;
    private final ReportDeliveryMapper deliveryMapper;
    private final ReportNotificationPort notificationPort;

    @Value("${report.retention-cleanup-enabled:true}")
    private boolean enabled;

    @Value("${report.retention-months:12}")
    private int retentionMonths;

    @Scheduled(cron = "${report.retention-cron:0 30 3 * * *}")
    @Transactional(rollbackFor = Exception.class)
    public void cleanupExpiredReports() {
        if (!enabled) return;
        List<ReportJob> expired = jobMapper.selectExpiredTerminalForUpdate(
                Math.max(1, retentionMonths), 500);
        if (expired.isEmpty()) return;
        List<Long> candidates = expired.stream().map(ReportJob::getId).toList();
        Set<Long> candidateSet = new HashSet<>(candidates);
        Set<Long> deletable = new HashSet<>(notificationPort.deleteTerminalIntentsForExpiredJobs(candidates));
        deletable.retainAll(candidateSet);
        if (deletable.isEmpty()) return;

        List<Long> ids = candidates.stream().filter(deletable::contains).toList();
        deliveryMapper.delete(new LambdaQueryWrapper<ReportDelivery>().in(ReportDelivery::getJobId, ids));
        attemptMapper.delete(new LambdaQueryWrapper<ReportJobAttempt>().in(ReportJobAttempt::getJobId, ids));
        jobMapper.deleteBatchIds(ids);
        log.info("【T9-2】报告留存清理完成 retentionMonths={}, jobs={}",
                Math.max(1, retentionMonths), ids.size());
    }
}
