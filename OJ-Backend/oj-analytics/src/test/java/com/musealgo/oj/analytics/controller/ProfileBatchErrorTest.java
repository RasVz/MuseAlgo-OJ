package com.musealgo.oj.analytics.controller;

import com.musealgo.oj.analytics.application.*;
import com.musealgo.oj.analytics.dto.BatchReportRequest;
import com.musealgo.oj.analytics.error.AnalyticsErrorCode;
import com.musealgo.oj.common.exception.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProfileBatchErrorTest {
    private BatchReportRequest request() {
        var request = new BatchReportRequest();
        request.setClassId(1L);
        request.setStudentIds(List.of(7L));
        return request;
    }
    private ProfileController controller(ReportJobService reports) {
        return new ProfileController(mock(ProfileApplicationService.class), mock(PracticeAnalyticsService.class),
                mock(ReportAccessService.class), reports);
    }
    @Test void sharedInvalidPeriodIsRejectedBeforeAnyStudentIsQueued() {
        ReportJobService reports = mock(ReportJobService.class);
        doThrow(new BusinessException(AnalyticsErrorCode.INVALID_PERIOD)).when(reports).validatePeriod(any(), any());
        var error = assertThrows(BusinessException.class, () -> controller(reports).generateBatchReports(request(), null, null));
        assertEquals("INVALID_PERIOD", error.getErrorCode().key());
        verify(reports, never()).requestReport(anyLong(), anyLong(), any(), any());
    }
    @Test void knownStudentRejectionRetainsStableReason() {
        ReportJobService reports = mock(ReportJobService.class);
        when(reports.requestReport(anyLong(), anyLong(), any(), any()))
                .thenThrow(new BusinessException(AnalyticsErrorCode.REPORT_JOB_NOT_FOUND));
        var result = controller(reports).generateBatchReports(request(), null, null);
        assertFalse(result.getData().get(0).isAccepted());
        assertEquals("REPORT_JOB_NOT_FOUND", result.getData().get(0).getErrorCode());
    }
    @Test void systemAndDependencyErrorsCannotBecomeBatchSuccess() {
        for (RuntimeException cause : List.of(new IllegalStateException("database private detail"),
                new DependencyUnavailableException(CommonErrorCode.DEPENDENCY_UNAVAILABLE, new RuntimeException()))) {
            ReportJobService reports = mock(ReportJobService.class);
            when(reports.requestReport(anyLong(), anyLong(), any(), any())).thenThrow(cause);
            assertSame(cause, assertThrows(RuntimeException.class,
                    () -> controller(reports).generateBatchReports(request(), null, null)));
        }
    }
}
