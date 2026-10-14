package top.everblue.oj.contest.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.Test;
import top.everblue.oj.contest.entity.Contest;
import top.everblue.oj.contest.entity.VpAttempt;
import top.everblue.oj.contest.mapper.ContestMapper;
import top.everblue.oj.contest.mapper.VpAttemptMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContestScheduleQueryAdapterTest {

    @Test
    @SuppressWarnings("unchecked")
    void runningOiVpQueryExcludesAttemptsFromOtherContestModes() {
        ContestMapper contestMapper = mock(ContestMapper.class);
        VpAttemptMapper vpAttemptMapper = mock(VpAttemptMapper.class);
        ContestScheduleQueryAdapter query = new ContestScheduleQueryAdapter(contestMapper, vpAttemptMapper);
        VpAttempt oiAttempt = runningAttempt(9L, 1L);
        VpAttempt acmAttempt = runningAttempt(10L, 2L);
        when(vpAttemptMapper.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(oiAttempt, acmAttempt));
        Contest oiContest = new Contest();
        oiContest.setId(1L);
        oiContest.setMode("OI");
        when(contestMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(oiContest));

        Set<Long> result = query.findRunningOiVpAttemptIds();

        assertEquals(Set.of(9L), result);
    }

    private static VpAttempt runningAttempt(Long id, Long contestId) {
        VpAttempt attempt = new VpAttempt();
        attempt.setId(id);
        attempt.setContestId(contestId);
        attempt.setStatus(VpAttempt.STATUS_RUNNING);
        attempt.setEndTime(LocalDateTime.now().plusMinutes(10));
        return attempt;
    }
}
