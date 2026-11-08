package com.musealgo.oj.controller;

import com.musealgo.oj.problem.mapper.ProblemCaseMapper;
import com.musealgo.oj.problem.service.TestCaseStorageService;
import com.musealgo.oj.problem.service.impl.ProblemCaseServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProblemCaseZipValidationTest {
    private final ProblemCaseMapper cases = mock(ProblemCaseMapper.class);
    private final TestCaseStorageService storage = mock(TestCaseStorageService.class);
    private final ProblemCaseServiceImpl service = new ProblemCaseServiceImpl(cases, storage);

    @Test void brokenZipIsAnInputErrorAndDoesNotTouchStoredCases() {
        var file = new MockMultipartFile("file", "broken.zip", "application/zip", "not a zip".getBytes());
        assertThrows(com.musealgo.oj.common.exception.BusinessException.class, () -> service.importCasesFromZip(file, 1L));
        verifyNoInteractions(cases, storage);
    }

    @Test void emptyZipIsAnInputError() {
        var file = new MockMultipartFile("file", "empty.zip", "application/zip", new byte[0]);
        assertThrows(com.musealgo.oj.common.exception.BusinessException.class, () -> service.importCasesFromZip(file, 1L));
        verifyNoInteractions(cases, storage);
    }
}
