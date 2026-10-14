package top.everblue.oj.system.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import top.everblue.oj.common.annotation.RateLimit;
import top.everblue.oj.common.api.Result;
import top.everblue.oj.system.service.MetricsEventService;

import java.util.ArrayList;
import java.util.List;

/**
 * 前端体验埋点上报接口（匿名）。
 * <p>
 * 仅接收聚合计数（路由耗时分布 / 接口失败 / JS 错误），不携带任何用户标识，
 * 单批上限 100 条。限频按 IP（60 次/分，覆盖机房 NAT 共享 IP 场景）。
 */
@Slf4j
@Tag(name = "体验埋点", description = "前端匿名体验数据上报（route/api_fail/js_error 聚合）")
@RestController
@RequiredArgsConstructor
@RequestMapping("/metrics")
public class MetricsController {

    /** 单批事件上限（防御超大请求体） */
    private static final int BATCH_MAX = 100;

    private final MetricsEventService metricsEventService;

    @Operation(summary = "批量上报埋点事件（匿名聚合）")
    @RateLimit(key = "metrics", time = 60, count = 60, byIp = true)
    @PostMapping("/batch")
    public Result<Void> batch(@RequestBody MetricsBatchRequest req) {
        if (req == null || req.getEvents() == null || req.getEvents().isEmpty()) {
            return Result.success(null);
        }
        List<EventItem> items = req.getEvents().size() > BATCH_MAX
                ? new ArrayList<>(req.getEvents().subList(0, BATCH_MAX))
                : req.getEvents();
        metricsEventService.record(items);
        return Result.success(null);
    }

    /** 批量上报请求体 */
    @Data
    public static class MetricsBatchRequest {
        private List<EventItem> events;
    }

    /** 单条事件：type 白名单见 {@link MetricsEventService#SUPPORTED_TYPES} */
    @Data
    public static class EventItem {
        /** 事件类型：route / route_slow / api_fail / js_error */
        private String type;
        /** 事件对象：路由名 / 接口路径 / 错误消息（后端截断至 120 字） */
        private String key;
        /** 当日累计次数（默认 1，上限 1000） */
        private Integer count = 1;
    }
}
