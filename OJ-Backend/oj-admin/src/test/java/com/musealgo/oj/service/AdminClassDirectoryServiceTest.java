package com.musealgo.oj.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.musealgo.oj.common.exception.BusinessException;
import com.musealgo.oj.dto.AdminClassMemberCount;
import com.musealgo.oj.dto.AdminClassSummary;
import com.musealgo.oj.mapper.AdminClassDirectoryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdminClassDirectoryServiceTest {

    private AdminClassDirectoryMapper mapper;
    private AdminClassDirectoryService service;

    @BeforeEach
    void setUp() {
        mapper = mock(AdminClassDirectoryMapper.class);
        service = new AdminClassDirectoryService(mapper);
    }

    @Test
    void trimsSearchAndUsesWhitelistedFilters() {
        var expected = new Page<AdminClassSummary>(2, 25, 30);
        when(mapper.selectPage(any(), eq("teacher"), isNull(), eq("hasMembers"), eq("oldest")))
                .thenReturn(expected);

        var actual = service.list(2, 25, "  teacher  ", "hasMembers", "oldest");

        assertSame(expected, actual);
        verify(mapper).selectPage(any(), eq("teacher"), isNull(), eq("hasMembers"), eq("oldest"));
    }

    @Test
    void parsesAnExactClassIdAndDefaultsOptionalFilters() {
        when(mapper.selectPage(any(), eq("42"), eq(42L), eq("all"), eq("newest")))
                .thenReturn(new Page<>(1, 20));

        service.list(1, 20, "42", null, null);

        verify(mapper).selectPage(any(), eq("42"), eq(42L), eq("all"), eq("newest"));
    }

    @Test
    void hydratesActualMemberCountsForOnlyTheCurrentPage() {
        var row = new AdminClassSummary();
        row.setId(42L);
        var expected = new Page<AdminClassSummary>(1, 20, 1);
        expected.setRecords(java.util.List.of(row));
        var count = new AdminClassMemberCount();
        count.setClassId(42L);
        count.setStudentCount(3L);
        when(mapper.selectPage(any(), eq(null), eq(null), eq("all"), eq("newest"))).thenReturn(expected);
        when(mapper.selectStudentCounts(eq(java.util.List.of(42L)))).thenReturn(java.util.List.of(count));

        var actual = service.list(1, 20, null, null, null);

        assertEquals(3L, actual.getRecords().get(0).getStudentCount());
        verify(mapper).selectStudentCounts(eq(java.util.List.of(42L)));
    }

    @Test
    void convertsBlankKeywordToNoSearch() {
        when(mapper.selectPage(any(), isNull(), isNull(), eq("all"), eq("newest")))
                .thenReturn(new Page<>(1, 20));

        service.list(1, 20, "  ", null, null);

        verify(mapper).selectPage(any(), isNull(), isNull(), eq("all"), eq("newest"));
    }

    @Test
    void supportsTheExistingTwoHundredRowOptionLimit() {
        when(mapper.selectPage(any(), isNull(), isNull(), eq("all"), eq("newest")))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var actual = service.list(1, 200, null, null, null);

        assertEquals(200, actual.getSize());
    }

    @Test
    void rejectsInvalidPaginationAndOverlongKeywordBeforeQuerying() {
        assertThrows(BusinessException.class, () -> service.list(0, 20, null, "all", "newest"));
        assertThrows(BusinessException.class, () -> service.list(1, 201, null, "all", "newest"));
        assertThrows(BusinessException.class, () -> service.list(1, 20, "x".repeat(101), "all", "newest"));
        verifyNoInteractions(mapper);
    }

    @Test
    void rejectsUnknownMemberFilterAndSortBeforeQuerying() {
        var memberError = assertThrows(BusinessException.class,
                () -> service.list(1, 20, null, "unknown", "newest"));
        var sortError = assertThrows(BusinessException.class,
                () -> service.list(1, 20, null, "all", "studentCount DESC"));

        assertEquals(400, memberError.getErrorCode().status());
        assertEquals(400, sortError.getErrorCode().status());
        verifyNoInteractions(mapper);
    }
}
