package com.musealgo.oj.api.report;

/**
 * 报告通知状态只读查询。调用方先由报告域校验当前用户有权读取 job，
 * 再以当前用户 ID 和当前终态修订查询通知事实。
 */
public interface ReportNotificationStatusQuery {

    /**
     * 查询指定 job、当前收件人和当前终态修订的通知意图及已读事实。
     * 只返回该收件人的通知事实；未记录的历史提醒返回空 intents，不回填历史意图。
     * 报告中心可用状态不属于此查询结果。
     *
     * @param jobId 报告 job ID
     * @param recipientId 经报告读取授权后的当前用户 ID
     * @param currentTerminalRevision 报告域当前终态修订
     * @return 当前修订的通知事实快照
     */
    ReportNotificationStatusSnapshot findForJob(
            Long jobId, Long recipientId, Long currentTerminalRevision);
}
