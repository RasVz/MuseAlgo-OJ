package top.everblue.oj.system;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import top.everblue.oj.system.controller.MetricsController.EventItem;
import top.everblue.oj.system.mapper.MetricsEventMapper;
import top.everblue.oj.system.service.impl.MetricsEventServiceImpl;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 埋点聚合服务单测：白名单过滤、key 截断、count 钳制、合法事件入库。
 */
class MetricsEventServiceTest {

    private MetricsEventMapper mapper;
    private MetricsEventServiceImpl service;

    @BeforeEach
    void setUp() {
        mapper = mock(MetricsEventMapper.class);
        service = new MetricsEventServiceImpl(mapper);
    }

    private EventItem item(String type, String key, Integer count) {
        EventItem item = new EventItem();
        item.setType(type);
        item.setKey(key);
        item.setCount(count);
        return item;
    }

    @Test
    void record_合法事件入库() {
        service.record(List.of(item("route", "Home", 1), item("api_fail", "/problem/list", 3)));

        verify(mapper, org.mockito.Mockito.times(2))
                .upsertCount(any(LocalDate.class), anyString(), anyString(), anyInt());
    }

    @Test
    void record_白名单外类型静默丢弃() {
        service.record(List.of(
                item("hacker_type", "/x", 1),
                item(null, "/y", 1),
                item("route", "", 1)));

        verify(mapper, never()).upsertCount(any(LocalDate.class), anyString(), anyString(), anyInt());
    }

    @Test
    void record_key超长截断至120字() {
        String longKey = "x".repeat(300);
        service.record(List.of(item("js_error", longKey, 1)));

        verify(mapper).upsertCount(any(LocalDate.class), anyString(),
                org.mockito.ArgumentMatchers.argThat(k -> k.length() == 120), anyInt());
    }

    @Test
    void record_count钳制() {
        service.record(List.of(
                item("route", "Home", null),    // null → 1
                item("route", "Home", 0),       // 0 → 1
                item("route", "Home", 99999))); // 超上限 → 1000

        verify(mapper, org.mockito.Mockito.times(2))
                .upsertCount(any(LocalDate.class), anyString(), anyString(), org.mockito.ArgumentMatchers.eq(1));
        verify(mapper, org.mockito.Mockito.times(1))
                .upsertCount(any(LocalDate.class), anyString(), anyString(), org.mockito.ArgumentMatchers.eq(1000));
    }

    @Test
    void record_空列表不报错() {
        service.record(List.of());
        service.record(null);
        verify(mapper, never()).upsertCount(any(LocalDate.class), anyString(), anyString(), anyInt());
    }
}
