package top.everblue.oj.analytics.application;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import top.everblue.oj.analytics.entity.PlagiarismReport;
import top.everblue.oj.analytics.mapper.PlagiarismReportMapper;
import top.everblue.oj.api.contest.ContestSchedule;
import top.everblue.oj.api.contest.ContestScheduleQuery;
import top.everblue.oj.api.submission.SubmissionCodeQuery;
import top.everblue.oj.api.submission.SubmissionCodeQuery.CodePairSource;
import top.everblue.oj.api.teaching.ClassQuery;
import top.everblue.oj.api.teaching.ClassQuery.ClassInfo;
import top.everblue.oj.common.exception.ForbiddenException;
import top.everblue.oj.common.security.AuthenticatedUser;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.mockito.ArgumentMatchers;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 查重服务单测：幂等跳过 / 阈值过滤 / 对规范化 / 空输入 / 比赛维度（M2）。
 */
@ExtendWith(MockitoExtension.class)
class PlagiarismServiceTest {

    private static final Long CLASS_ID = 1L;
    private static final Long PROBLEM_ID = 10L;
    private static final Long TEACHER_ID = 100L;
    private static final Long CONTEST_ID = 50L;
    private static final Long CONTEST_AUTHOR_ID = 200L;

    private static final String CODE_A = """
            #include <bits/stdc++.h>
            using namespace std;
            int main() {
                long long a, b;
                cin >> a >> b;
                cout << a + b << endl;
                return 0;
            }
            """;
    /** 与 CODE_A 同思路（改名+注释），相似度 ≈1 */
    private static final String CODE_A2 = """
            // variant
            #include <bits/stdc++.h>
            using namespace std;
            int main() {
                long long x, y;
                cin >> x >> y;
                cout << x + y << endl;
                return 0;
            }
            """;
    /** 完全不同思路，与 A 相似度 <0.5 */
    private static final String CODE_B = """
            #include <bits/stdc++.h>
            using namespace std;
            int main() {
                int n;
                cin >> n;
                int sum = 0;
                for (int i = 1; i <= n; i++) sum += i;
                cout << sum << endl;
                return 0;
            }
            """;

    @Mock
    private SubmissionCodeQuery submissionCodeQuery;
    @Mock
    private ClassQuery classQuery;
    @Mock
    private ContestScheduleQuery contestScheduleQuery;
    @Mock
    private PlagiarismReportMapper reportMapper;

    private PlagiarismService newService() {
        return new PlagiarismService(submissionCodeQuery, classQuery, contestScheduleQuery, reportMapper);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void duplicateKeyConflictIsSkippedIdempotently() {
        loginAsTeacher(TEACHER_ID);
        when(classQuery.findById(CLASS_ID))
                .thenReturn(Optional.of(new ClassInfo(CLASS_ID, "一班", TEACHER_ID)));
        when(submissionCodeQuery.findByClassAndProblem(anyLong(), anyLong(), any()))
                .thenReturn(List.of(
                        src(1L, 11L, CODE_A),
                        src(2L, 12L, CODE_A2)));
        // 第一次执行：成功入库
        when(reportMapper.insert(ArgumentMatchers.<PlagiarismReport>any())).thenReturn(1);
        assertEquals(1, newService().runPlagiarismCheck(CLASS_ID, PROBLEM_ID));
        // 第二次执行：唯一键冲突 → 幂等跳过，返回 0
        when(reportMapper.insert(ArgumentMatchers.<PlagiarismReport>any()))
                .thenThrow(new DuplicateKeyException("uk_pair conflict"));
        assertEquals(0, newService().runPlagiarismCheck(CLASS_ID, PROBLEM_ID));
    }

    @Test
    void lowSimilarityPairIsNotPersisted() {
        loginAsTeacher(TEACHER_ID);
        when(classQuery.findById(CLASS_ID))
                .thenReturn(Optional.of(new ClassInfo(CLASS_ID, "一班", TEACHER_ID)));
        when(submissionCodeQuery.findByClassAndProblem(anyLong(), anyLong(), any()))
                .thenReturn(List.of(
                        src(1L, 11L, CODE_A),
                        src(2L, 12L, CODE_B)));
        assertEquals(0, newService().runPlagiarismCheck(CLASS_ID, PROBLEM_ID));
        verify(reportMapper, never()).insert(ArgumentMatchers.<PlagiarismReport>any());
    }

    @Test
    void pairIsNormalizedWithSmallerUserIdFirst() {
        loginAsTeacher(TEACHER_ID);
        when(classQuery.findById(CLASS_ID))
                .thenReturn(Optional.of(new ClassInfo(CLASS_ID, "一班", TEACHER_ID)));
        // 提交顺序故意乱序：user 20 在前、user 10 在后
        when(submissionCodeQuery.findByClassAndProblem(anyLong(), anyLong(), any()))
                .thenReturn(List.of(
                        src(2L, 20L, CODE_A),
                        src(1L, 10L, CODE_A2)));
        when(reportMapper.insert(ArgumentMatchers.<PlagiarismReport>any())).thenReturn(1);

        assertEquals(1, newService().runPlagiarismCheck(CLASS_ID, PROBLEM_ID));

        ArgumentCaptor<PlagiarismReport> captor = ArgumentCaptor.forClass(PlagiarismReport.class);
        verify(reportMapper).insert(captor.capture());
        PlagiarismReport saved = captor.getValue();
        assertEquals(10L, saved.getUserAId());
        assertEquals(20L, saved.getUserBId());
        assertEquals(1L, saved.getSubmissionAId());
        assertEquals(2L, saved.getSubmissionBId());
        assertTrue(saved.getSimilarity() >= 0.7);
    }

    @Test
    void insufficientSubmissionsReturnsZero() {
        loginAsTeacher(TEACHER_ID);
        when(classQuery.findById(CLASS_ID))
                .thenReturn(Optional.of(new ClassInfo(CLASS_ID, "一班", TEACHER_ID)));
        when(submissionCodeQuery.findByClassAndProblem(anyLong(), anyLong(), any()))
                .thenReturn(List.of(src(1L, 11L, CODE_A)));
        assertEquals(0, newService().runPlagiarismCheck(CLASS_ID, PROBLEM_ID));
        verify(reportMapper, never()).insert(ArgumentMatchers.<PlagiarismReport>any());
    }

    @Test
    void blankCodeSubmissionsAreFilteredOut() {
        loginAsTeacher(TEACHER_ID);
        when(classQuery.findById(CLASS_ID))
                .thenReturn(Optional.of(new ClassInfo(CLASS_ID, "一班", TEACHER_ID)));
        when(submissionCodeQuery.findByClassAndProblem(anyLong(), anyLong(), any()))
                .thenReturn(List.of(
                        src(1L, 11L, CODE_A),
                        src(2L, 12L, "   "),
                        src(3L, 13L, "x")));
        assertEquals(0, newService().runPlagiarismCheck(CLASS_ID, PROBLEM_ID));
        verify(reportMapper, never()).insert(ArgumentMatchers.<PlagiarismReport>any());
    }

    // ------------------------------------------------------------------
    // M2：比赛维度跨班比对 + 权限
    // ------------------------------------------------------------------

    @Test
    void contestCheckPersistsReportWithContestIdAndNullClass() {
        loginAsTeacher(CONTEST_AUTHOR_ID);
        when(contestScheduleQuery.findById(CONTEST_ID))
                .thenReturn(Optional.of(new ContestSchedule(
                        CONTEST_ID, LocalDateTime.now().minusHours(2),
                        LocalDateTime.now().minusHours(1), "OI", CONTEST_AUTHOR_ID)));
        when(submissionCodeQuery.findContestProblemIds(CONTEST_ID))
                .thenReturn(List.of(PROBLEM_ID));
        when(submissionCodeQuery.findByContestAndProblem(CONTEST_ID, PROBLEM_ID, null))
                .thenReturn(List.of(
                        src(1L, 11L, CODE_A),
                        src(2L, 22L, CODE_A2)));
        when(reportMapper.insert(ArgumentMatchers.<PlagiarismReport>any())).thenReturn(1);

        assertEquals(1, newService().runContestPlagiarismCheck(CONTEST_ID));

        ArgumentCaptor<PlagiarismReport> captor = ArgumentCaptor.forClass(PlagiarismReport.class);
        verify(reportMapper).insert(captor.capture());
        PlagiarismReport saved = captor.getValue();
        assertEquals(CONTEST_ID, saved.getContestId());
        assertNull(saved.getClassId());
        assertEquals(PROBLEM_ID, saved.getProblemId());
    }

    @Test
    void contestCheckRunsAllProblemsAndSumsReports() {
        loginAsTeacher(CONTEST_AUTHOR_ID);
        when(contestScheduleQuery.findById(CONTEST_ID))
                .thenReturn(Optional.of(new ContestSchedule(
                        CONTEST_ID, LocalDateTime.now().minusHours(2),
                        LocalDateTime.now().minusHours(1), "ACM", CONTEST_AUTHOR_ID)));
        when(submissionCodeQuery.findContestProblemIds(CONTEST_ID))
                .thenReturn(List.of(10L, 11L));
        // 题 10：1 对疑似；题 11：不足 2 份
        when(submissionCodeQuery.findByContestAndProblem(CONTEST_ID, 10L, null))
                .thenReturn(List.of(src(1L, 11L, CODE_A), src(2L, 22L, CODE_A2)));
        when(submissionCodeQuery.findByContestAndProblem(CONTEST_ID, 11L, null))
                .thenReturn(List.of(src(3L, 33L, CODE_A)));
        when(reportMapper.insert(ArgumentMatchers.<PlagiarismReport>any())).thenReturn(1);

        assertEquals(1, newService().runContestPlagiarismCheck(CONTEST_ID));
        verify(reportMapper, times(1)).insert(ArgumentMatchers.<PlagiarismReport>any());
    }

    @Test
    void contestCheckRejectsNonAuthorTeacher() {
        loginAsTeacher(TEACHER_ID);
        when(contestScheduleQuery.findById(CONTEST_ID))
                .thenReturn(Optional.of(new ContestSchedule(
                        CONTEST_ID, LocalDateTime.now().minusHours(2),
                        LocalDateTime.now().minusHours(1), "OI", CONTEST_AUTHOR_ID)));
        assertThrows(ForbiddenException.class,
                () -> newService().runContestPlagiarismCheck(CONTEST_ID));
        verify(reportMapper, never()).insert(ArgumentMatchers.<PlagiarismReport>any());
    }

    @Test
    void contestCheckWithoutSubmissionsReturnsZero() {
        loginAsTeacher(CONTEST_AUTHOR_ID);
        when(contestScheduleQuery.findById(CONTEST_ID))
                .thenReturn(Optional.of(new ContestSchedule(
                        CONTEST_ID, LocalDateTime.now().minusHours(2),
                        LocalDateTime.now().minusHours(1), "OI", CONTEST_AUTHOR_ID)));
        when(submissionCodeQuery.findContestProblemIds(CONTEST_ID)).thenReturn(List.of());
        assertEquals(0, newService().runContestPlagiarismCheck(CONTEST_ID));
        verify(reportMapper, never()).insert(ArgumentMatchers.<PlagiarismReport>any());
    }

    // ------------------------------------------------------------------
    // 报告处置状态更新（已核实 / 已忽略）
    // ------------------------------------------------------------------

    @Test
    void teacherCanMarkOwnClassReportAsReviewed() {
        loginAsTeacher(TEACHER_ID);
        when(reportMapper.selectById(1L)).thenReturn(reportWith(CLASS_ID, null, "PENDING"));
        when(classQuery.findById(CLASS_ID))
                .thenReturn(Optional.of(new ClassInfo(CLASS_ID, "一班", TEACHER_ID)));

        newService().updateReportStatus(1L, "REVIEWED");

        ArgumentCaptor<PlagiarismReport> captor = ArgumentCaptor.forClass(PlagiarismReport.class);
        verify(reportMapper).updateById(captor.capture());
        assertEquals(1L, captor.getValue().getId());
        assertEquals("REVIEWED", captor.getValue().getStatus());
    }

    @Test
    void nonOwnerTeacherCannotUpdateClassReport() {
        loginAsTeacher(999L); // 非该班班主任
        when(reportMapper.selectById(1L)).thenReturn(reportWith(CLASS_ID, null, "PENDING"));
        when(classQuery.findById(CLASS_ID))
                .thenReturn(Optional.of(new ClassInfo(CLASS_ID, "一班", TEACHER_ID)));

        assertThrows(ForbiddenException.class,
                () -> newService().updateReportStatus(1L, "IGNORED"));
        verify(reportMapper, never()).updateById(ArgumentMatchers.<PlagiarismReport>any());
    }

    @Test
    void contestReportRequiresAuthorPermission() {
        loginAsTeacher(TEACHER_ID); // 非比赛创建者
        when(reportMapper.selectById(1L)).thenReturn(reportWith(null, CONTEST_ID, "PENDING"));
        when(contestScheduleQuery.findById(CONTEST_ID))
                .thenReturn(Optional.of(new ContestSchedule(
                        CONTEST_ID, LocalDateTime.now().minusHours(2),
                        LocalDateTime.now().minusHours(1), "OI", CONTEST_AUTHOR_ID)));

        assertThrows(ForbiddenException.class,
                () -> newService().updateReportStatus(1L, "REVIEWED"));
        verify(reportMapper, never()).updateById(ArgumentMatchers.<PlagiarismReport>any());
    }

    @Test
    void invalidStatusIsRejected() {
        loginAsTeacher(TEACHER_ID);
        assertThrows(IllegalArgumentException.class,
                () -> newService().updateReportStatus(1L, "FOO"));
        verify(reportMapper, never()).selectById(anyLong());
        verify(reportMapper, never()).updateById(ArgumentMatchers.<PlagiarismReport>any());
    }

    @Test
    void missingReportIsRejected() {
        loginAsTeacher(TEACHER_ID);
        when(reportMapper.selectById(1L)).thenReturn(null);
        assertThrows(IllegalArgumentException.class,
                () -> newService().updateReportStatus(1L, "REVIEWED"));
        verify(reportMapper, never()).updateById(ArgumentMatchers.<PlagiarismReport>any());
    }

    // ------------------------------------------------------------------

    private static PlagiarismReport reportWith(Long classId, Long contestId, String status) {
        PlagiarismReport r = new PlagiarismReport();
        r.setId(1L);
        r.setClassId(classId);
        r.setContestId(contestId);
        r.setStatus(status);
        return r;
    }

    private static CodePairSource src(Long submissionId, Long userId, String code) {
        return new CodePairSource(submissionId, userId, PROBLEM_ID, code,
                "1", LocalDateTime.now());
    }

    private void loginAsTeacher(Long userId) {
        AuthenticatedUser user = () -> userId;
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null,
                        List.of(new SimpleGrantedAuthority("ROLE_TEACHER"))));
    }
}
