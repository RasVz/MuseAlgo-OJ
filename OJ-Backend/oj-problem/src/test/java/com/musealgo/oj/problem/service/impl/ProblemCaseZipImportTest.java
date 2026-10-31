package com.musealgo.oj.problem.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import com.musealgo.oj.problem.application.ProblemCaseApplicationService;
import com.musealgo.oj.problem.entity.ProblemCase;
import com.musealgo.oj.problem.mapper.ProblemCaseMapper;
import com.musealgo.oj.problem.service.TestCaseStorageService;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ZIP 导入的端到端行为（真实 ZIP 字节 + 真实临时文件目录，仅 Mapper 用 mock）。
 * <p>
 * 覆盖三点：文件名按数值排序决定 case_order、用例分值统一为 100/n、
 * 以及「配不出用例/超过上限时先拒绝、不清理旧用例」。
 * 这只验证文件与解析链路，数据库写入不在覆盖范围内。
 * </p>
 */
class ProblemCaseZipImportTest {

    private static final Long PROBLEM_ID = 5L;

    @TempDir
    Path tempDir;

    private ProblemCaseMapper problemCaseMapper;
    private ProblemCaseServiceImpl service;

    @BeforeEach
    void setUp() {
        problemCaseMapper = mock(ProblemCaseMapper.class);
        TestCaseStorageService storage = new TestCaseStorageService(tempDir.toString());
        service = new ProblemCaseServiceImpl(problemCaseMapper, storage);

        AtomicLong idSequence = new AtomicLong(1000);
        doAnswer(invocation -> {
            ProblemCase row = invocation.getArgument(0);
            row.setId(idSequence.incrementAndGet());
            return 1;
        }).when(problemCaseMapper).insert(any(ProblemCase.class));
        when(problemCaseMapper.updateById(any(ProblemCase.class))).thenReturn(1);
        when(problemCaseMapper.delete(any())).thenReturn(1);
    }

    /** 按给定顺序把 (文件名, 内容) 写进一个 ZIP。 */
    private byte[] zip(List<String[]> entries) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (String[] entry : entries) {
                zip.putNextEntry(new ZipEntry(entry[0]));
                zip.write(entry[1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private org.springframework.web.multipart.MultipartFile file(byte[] content, String name) {
        return new org.springframework.mock.web.MockMultipartFile("file", name, "application/zip", content);
    }

    @Test
    void numericFileNamesDecideCaseOrderAndScoresSplitHundred() throws Exception {
        // 刻意按 10 -> 2 -> 1 的顺序写入 ZIP，验证排序不依赖压缩包内顺序
        byte[] archive = zip(List.of(
                new String[]{"10.in", "ten"}, new String[]{"10.out", "TEN"},
                new String[]{"2.in", "two"}, new String[]{"2.out", "TWO"},
                new String[]{"1.in", "one"}, new String[]{"1.out", "ONE"}));

        int imported = service.importCasesFromZip(file(archive, "cases.zip"), PROBLEM_ID);

        assertThat(imported).isEqualTo(3);
        ArgumentCaptor<ProblemCase> inserted = ArgumentCaptor.forClass(ProblemCase.class);
        verify(problemCaseMapper, times(3)).insert(inserted.capture());
        assertThat(inserted.getAllValues())
                .extracting(ProblemCase::getCaseOrder, ProblemCase::getScore)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, 34),
                        org.assertj.core.groups.Tuple.tuple(2, 33),
                        org.assertj.core.groups.Tuple.tuple(3, 33));
    }

    @Test
    void zipWithoutPairedCasesIsRejectedWithoutDeletingExistingOnes() throws Exception {
        byte[] archive = zip(List.of(
                new String[]{"1.in", "one"},
                new String[]{"2.out", "two"}));

        assertThatThrownBy(() -> service.importCasesFromZip(file(archive, "broken.zip"), PROBLEM_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未找到成对");

        verify(problemCaseMapper, never()).delete(any());
        assertThat(tempDir.resolve(String.valueOf(PROBLEM_ID))).doesNotExist();
    }

    @Test
    void zipOverCaseLimitIsRejectedBeforeClearing() throws Exception {
        int overLimit = ProblemCaseApplicationService.MAX_CASES_PER_PROBLEM + 1;
        List<String[]> entries = new java.util.ArrayList<>(overLimit * 2);
        for (int i = 1; i <= overLimit; i++) {
            entries.add(new String[]{i + ".in", "in" + i});
            entries.add(new String[]{i + ".out", "out" + i});
        }

        assertThatThrownBy(() -> service.importCasesFromZip(file(zip(entries), "huge.zip"), PROBLEM_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("上限");

        verify(problemCaseMapper, never()).delete(any());
        assertThat(tempDir.resolve(String.valueOf(PROBLEM_ID))).doesNotExist();
    }
}
