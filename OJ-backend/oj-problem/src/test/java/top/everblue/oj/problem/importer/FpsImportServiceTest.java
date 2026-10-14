package top.everblue.oj.problem.importer;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import top.everblue.oj.problem.application.ProblemManagementApplicationService;

import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FpsImportServiceTest {

    @Test
    void continuesImportingAndReportsEachProblemWhenOneProblemFails() {
        String xml = """
                <fps>
                  <item><title>Accepted</title><test_input>1</test_input><test_output>1</test_output></item>
                  <item><title>Rejected</title><test_input>2</test_input><test_output>2</test_output></item>
                </fps>
                """;
        MockMultipartFile file = new MockMultipartFile(
                "file", "problems.xml", "application/xml", xml.getBytes(StandardCharsets.UTF_8));
        ProblemManagementApplicationService problemManagement = mock(ProblemManagementApplicationService.class);
        when(problemManagement.createFromFps(any(FpsProblem.class), eq(7L)))
                .thenAnswer(invocation -> {
                    FpsProblem problem = invocation.getArgument(0);
                    if ("Accepted".equals(problem.title())) return 101L;
                    throw new IllegalStateException("数据库拒绝该题");
                });

        FpsImportService service = new FpsImportService(
                new FpsProblemSetParser(), problemManagement);

        FpsImportResult result = service.importFile(file, 7L);

        assertThat(result.total()).isEqualTo(2);
        assertThat(result.succeeded()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.items()).containsExactly(
                FpsImportItemResult.success(1, "Accepted", 101L, 1),
                FpsImportItemResult.failure(2, "Rejected", "数据库拒绝该题")
        );
    }

    @Test
    void importsValidItemsWhenAnotherItemCannotBeParsed() {
        String xml = """
                <fps>
                  <item><title>Broken limit</title><time_limit unit="fortnight">1</time_limit>
                    <test_input>1</test_input><test_output>1</test_output></item>
                  <item><title>Valid</title><test_input>1</test_input><test_output>1</test_output></item>
                </fps>
                """;
        MockMultipartFile file = new MockMultipartFile(
                "file", "problems.xml", "application/xml", xml.getBytes(StandardCharsets.UTF_8));
        ProblemManagementApplicationService problemManagement = mock(ProblemManagementApplicationService.class);
        when(problemManagement.createFromFps(any(FpsProblem.class), eq(7L))).thenReturn(102L);

        FpsImportResult result = new FpsImportService(new FpsProblemSetParser(), problemManagement)
                .importFile(file, 7L);

        assertThat(result.succeeded()).isEqualTo(2);
        assertThat(result.failed()).isZero();
        assertThat(result.items().get(0).message()).contains("不支持的时间单位");
        assertThat(result.items().get(1).problemId()).isEqualTo(102L);
    }

    @Test
    void importsFpsXmlFromAZipArchive() throws IOException {
        String xml = """
                <fps><item><title>Zipped</title>
                  <test_input>1</test_input><test_output>1</test_output>
                </item></fps>
                """;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("problemset.xml"));
            zip.write(xml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        MockMultipartFile file = new MockMultipartFile(
                "file", "problemset.zip", "application/zip", bytes.toByteArray());
        ProblemManagementApplicationService problemManagement = mock(ProblemManagementApplicationService.class);
        when(problemManagement.createFromFps(any(FpsProblem.class), eq(7L))).thenReturn(103L);

        FpsImportResult result = new FpsImportService(new FpsProblemSetParser(), problemManagement)
                .importFile(file, 7L);

        assertThat(result.succeeded()).isEqualTo(1);
        assertThat(result.items().get(0).title()).isEqualTo("Zipped");
        assertThat(result.items().get(0).problemId()).isEqualTo(103L);
    }

    @Test
    void continuesWithOtherXmlEntriesWhenOneZipEntryIsMalformed() throws IOException {
        String validXml = """
                <fps><item><title>Valid in zip</title>
                  <test_input>1</test_input><test_output>1</test_output>
                </item></fps>
                """;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("broken.xml"));
            zip.write("not xml".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("valid.xml"));
            zip.write(validXml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        MockMultipartFile file = new MockMultipartFile(
                "file", "problemset.zip", "application/zip", bytes.toByteArray());
        ProblemManagementApplicationService problemManagement = mock(ProblemManagementApplicationService.class);
        when(problemManagement.createFromFps(any(FpsProblem.class), eq(7L))).thenReturn(104L);

        FpsImportResult result = new FpsImportService(new FpsProblemSetParser(), problemManagement)
                .importFile(file, 7L);

        assertThat(result.total()).isEqualTo(2);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.succeeded()).isEqualTo(1);
        assertThat(result.items().get(0).title()).isEqualTo("broken.xml");
        assertThat(result.items().get(1).problemId()).isEqualTo(104L);
    }

    @Test
    void rejectsZipArchivesWithExcessiveIrrelevantEntries() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (int index = 0; index <= 1000; index++) {
                zip.putNextEntry(new ZipEntry("ignored-" + index + ".txt"));
                zip.closeEntry();
            }
        }
        MockMultipartFile file = new MockMultipartFile(
                "file", "too-many-entries.zip", "application/zip", bytes.toByteArray());

        FpsImportService service = new FpsImportService(
                new FpsProblemSetParser(), mock(ProblemManagementApplicationService.class));

        assertThatThrownBy(() -> service.importFile(file, 7L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("最多支持 1000 个条目");
    }
}
