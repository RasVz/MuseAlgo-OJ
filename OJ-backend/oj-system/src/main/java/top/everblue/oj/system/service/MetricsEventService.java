package top.everblue.oj.system.service;

import top.everblue.oj.system.controller.MetricsController.EventItem;

import java.util.List;

/**
 * 前端体验埋点聚合服务。
 * <p>
 * 仅做匿名聚合计数，不关联任何用户；对上报内容做白名单与长度防御。
 */
public interface MetricsEventService {

    /** 白名单事件类型 */
    java.util.Set<String> SUPPORTED_TYPES = java.util.Set.of(
            "route", "route_slow", "api_fail", "js_error");

    /**
     * 批量记录事件（逐条 upsert 当日计数）。
     *
     * @param items 事件列表（Controller 已限长 100 条）
     */
    void record(List<EventItem> items);
}
