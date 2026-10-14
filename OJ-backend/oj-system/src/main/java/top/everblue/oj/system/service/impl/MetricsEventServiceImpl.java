package top.everblue.oj.system.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.everblue.oj.system.controller.MetricsController.EventItem;
import top.everblue.oj.system.mapper.MetricsEventMapper;
import top.everblue.oj.system.service.MetricsEventService;

import java.time.LocalDate;
import java.util.List;

/**
 * 前端体验埋点聚合服务实现。
 * <p>
 * 防御策略：类型白名单过滤、key 截断至 120 字、count 上限钳制，
 * 非法条目静默丢弃（埋点数据低价值，不因脏数据抛错影响主链路）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetricsEventServiceImpl implements MetricsEventService {

    private static final int KEY_MAX_LEN = 120;
    private static final int COUNT_MAX = 1000;

    private final MetricsEventMapper metricsEventMapper;

    @Override
    public void record(List<EventItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        LocalDate today = LocalDate.now();
        int recorded = 0;
        for (EventItem item : items) {
            if (item == null || item.getType() == null || item.getKey() == null) {
                continue;
            }
            String type = item.getType().trim();
            if (!SUPPORTED_TYPES.contains(type)) {
                continue; // 白名单外类型静默丢弃
            }
            String key = item.getKey().trim();
            if (key.isEmpty() || key.length() > KEY_MAX_LEN) {
                key = key.substring(0, Math.min(key.length(), KEY_MAX_LEN));
            }
            if (key.isEmpty()) {
                continue;
            }
            int count = item.getCount() == null ? 1 : Math.min(Math.max(item.getCount(), 1), COUNT_MAX);
            metricsEventMapper.upsertCount(today, type, key, count);
            recorded++;
        }
        if (recorded > 0) {
            log.debug("metrics 埋点入库 {} 条（day={}）", recorded, today);
        }
    }
}
