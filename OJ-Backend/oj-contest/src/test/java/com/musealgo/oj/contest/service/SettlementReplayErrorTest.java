package com.musealgo.oj.contest.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.musealgo.oj.api.submission.ContestSubmissionQuery;
import com.musealgo.oj.api.user.UserRatingCommand;
import com.musealgo.oj.common.exception.BusinessException;
import com.musealgo.oj.contest.entity.ContestSettlementReplay;
import com.musealgo.oj.contest.mapper.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class SettlementReplayErrorTest {
    final ContestMapper contests = mock(ContestMapper.class);
    final ContestSettlementReplayMapper replays = mock(ContestSettlementReplayMapper.class);
    final ContestSettlementReplayService service = new ContestSettlementReplayService(contests, mock(ContestRankMapper.class),
        mock(ContestSettlementMapper.class), replays, mock(ContestSubmissionQuery.class), new ContestRankCalculator(),
        mock(RatingCalculator.class), mock(UserRatingService.class), mock(UserRatingCommand.class), mock(ContestUserMapper.class),
        new ObjectMapper().findAndRegisterModules(), mock(UserRatingMapper.class));
    @org.junit.jupiter.api.BeforeAll static void metadata() {
        for (var entity : java.util.List.of(com.musealgo.oj.contest.entity.ContestRank.class,
                com.musealgo.oj.contest.entity.ContestSettlement.class, com.musealgo.oj.contest.entity.ContestUser.class, com.musealgo.oj.contest.entity.UserRating.class)) {
            com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new org.apache.ibatis.session.Configuration(), "replay"), entity);
        }
    }
    @Test void missingReplayStartIsNotInternalServerFailure() {
        var error = assertThrows(BusinessException.class, () -> service.preview(99L));
        assertEquals("CONTEST_NOT_FOUND", error.getErrorCode().key());
    }
    @Test void unsupportedBackupVersionKeepsTheKnownRejection() {
        var replay = new ContestSettlementReplay(); replay.setId(1L); replay.setStatus("COMPLETED");
        replay.setBackupJson("{\"version\":1,\"before\":null,\"afterFingerprint\":null}");
        when(replays.selectById(1L)).thenReturn(replay);
        var error = assertThrows(BusinessException.class, () -> service.previewRestore(1L));
        assertEquals("CONTEST_INPUT_INVALID", error.getErrorCode().key());
    }
    @Test void replayWithFailedAuditInsertAbortsBeforeChangingResults() {
        var contest = new com.musealgo.oj.contest.entity.Contest(); contest.setId(1L);
        contest.setEndTime(java.time.LocalDateTime.now().minusDays(1)); contest.setRankFinalizedAt(java.time.LocalDateTime.now());
        when(contests.selectFinalizedForReplay()).thenReturn(java.util.List.of(contest));
        when(contests.lockSettlement()).thenReturn(1);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "enabled", true);
        String confirmation = service.preview(1L).confirmation();
        assertThrows(IllegalStateException.class, () -> service.execute(1L, "synthetic", 1L, confirmation));
    }

}
