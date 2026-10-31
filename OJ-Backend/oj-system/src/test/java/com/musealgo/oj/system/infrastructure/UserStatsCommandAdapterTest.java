package com.musealgo.oj.system.infrastructure;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.musealgo.oj.system.service.UserService;

import static org.mockito.Mockito.verify;

/**
 * {@link UserStatsCommandAdapter} 单测（T8）：契约方法必须委托给 oj-system 的 UserService 实现。
 * <p>
 * 适配器本身只做模块边界转发，这里用 mock 锁定「契约入口 → 数据归属方服务」这一层映射，
 * 防止后续重命名/改签名时静默丢实现（例如新增了 recalculateCounters 却忘了转发）。
 */
@ExtendWith(MockitoExtension.class)
class UserStatsCommandAdapterTest {

    @Mock
    private UserService userService;

    @InjectMocks
    private UserStatsCommandAdapter adapter;

    @Test
    void recalculateCountersDelegatesToUserService() {
        adapter.recalculateCounters(42L);

        verify(userService).recalculateCounters(42L);
    }

    @Test
    void incrementSubmissionCountDelegatesToUserService() {
        adapter.incrementSubmissionCount(42L);

        verify(userService).incrementSubmitCount(42L);
    }

    @Test
    void incrementAcceptedProblemCountDelegatesToUserService() {
        adapter.incrementAcceptedProblemCount(42L);

        verify(userService).incrementAcceptedCount(42L);
    }
}
