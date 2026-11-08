package com.musealgo.oj.problem.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.musealgo.oj.common.exception.BusinessException;
import com.musealgo.oj.problem.entity.ProblemList;
import com.musealgo.oj.problem.entity.dto.ProblemListOrderDTO;
import com.musealgo.oj.problem.mapper.ProblemListItemMapper;
import com.musealgo.oj.problem.mapper.ProblemListMapper;
import com.musealgo.oj.problem.service.impl.ProblemListServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ProblemListSortFailureTest {
    private final ProblemListItemMapper items=mock(ProblemListItemMapper.class);
    private final ProblemListMapper lists=mock(ProblemListMapper.class);
    private final ProblemListServiceImpl service=new ProblemListServiceImpl(items,mock(ProblemListAccessService.class));
    ProblemListSortFailureTest() {
        ReflectionTestUtils.setField(service,"baseMapper",lists);
        ReflectionTestUtils.setField(service,"entityClass",ProblemList.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(),"sort-error-test"),ProblemList.class);
    }
    @Test void partialSortDoesNotResetOmittedLists() {
        when(lists.selectCount(any())).thenReturn(1L);
        when(lists.update(any(),any())).thenReturn(1);
        service.updateSort(1,List.of(3L));
        verify(lists,times(1)).update(any(),any());
    }
    @Test void sortingANonmemberMustNotClaimTheWholeOrderWasSaved() {
        ProblemListOrderDTO input=new ProblemListOrderDTO(); input.setListId(3L); input.setProblemIds(List.of(11L,12L));
        when(items.update(any(),any())).thenReturn(1,0);
        var error=assertThrows(BusinessException.class,()->service.updateProblemOrder(input));
        assertEquals("PROBLEM_LIST_ITEM_NOT_FOUND",error.getErrorCode().key());
    }
    @Test void sortIdsFromAnotherTypeMustNotBeASilentSuccess() {
        when(lists.selectCount(any())).thenReturn(0L);
        var error=assertThrows(BusinessException.class,()->service.updateSort(1,List.of(3L)));
        assertEquals(400,error.getErrorCode().status());
    }
    @Test void failedIndividualListSortMustNotClaimSuccess() {
        when(lists.selectCount(any())).thenReturn(1L);
        when(lists.update(any(),any())).thenReturn(0);
        assertThrows(IllegalStateException.class,()->service.updateSort(1,List.of(3L)));
    }
}
