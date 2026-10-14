package top.everblue.oj.ai.infrastructure;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.everblue.oj.ai.mapper.AiHintTraceMapper;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * HintTraceQuery 契约适配器：DB 行 → 契约返回值转换。
 */
@ExtendWith(MockitoExtension.class)
class HintTraceQueryAdapterTest {

    @Mock
    private AiHintTraceMapper mapper;

    @Test
    void maxLevelByProblemConvertsRows() {
        when(mapper.selectMaxLevelByUsers(10L, List.of(1L, 2L)))
                .thenReturn(List.of(
                        Map.of("userId", 1L, "maxLevel", 4),
                        Map.of("userId", 2L, "maxLevel", 2)));

        HintTraceQueryAdapter adapter = new HintTraceQueryAdapter(mapper);
        Map<Long, Integer> result = adapter.maxLevelByProblem(10L, List.of(1L, 2L));

        assertEquals(4, result.get(1L));
        assertEquals(2, result.get(2L));
        assertEquals(2, result.size());
    }

    @Test
    void maxLevelByProblemSkipsQueryForEmptyUsers() {
        HintTraceQueryAdapter adapter = new HintTraceQueryAdapter(mapper);
        assertEquals(Map.of(), adapter.maxLevelByProblem(10L, List.of()));
        verify(mapper, never()).selectMaxLevelByUsers(any(), any());
    }

    @Test
    void countByLevelConvertsRows() {
        when(mapper.selectCountByLevel(10L)).thenReturn(List.of(
                Map.of("level", 1, "cnt", 12L),
                Map.of("level", 4, "cnt", 2L)));

        HintTraceQueryAdapter adapter = new HintTraceQueryAdapter(mapper);
        Map<Integer, Long> result = adapter.countByLevel(10L);

        assertEquals(12L, result.get(1));
        assertEquals(2L, result.get(4));
        assertEquals(2, result.size());
    }
}
