package com.musealgo.oj.ai.reference;
import com.musealgo.oj.ai.mapper.OfficialSolutionMapper;
import com.musealgo.oj.ai.diagnostics.DiagnosticStats;
import com.musealgo.oj.api.system.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class TutorReferenceServiceTest {
    final OfficialSolutionMapper mapper=mock(OfficialSolutionMapper.class);final SystemConfigQuery config=mock(SystemConfigQuery.class);final SystemConfigCommand command=mock(SystemConfigCommand.class);final DiagnosticStats stats=mock(DiagnosticStats.class);
    final TutorReferenceService service=new TutorReferenceService(mapper,config,command,stats);
    @Test void chosenReferenceKeepsHeadAndTailAndReportsExcerpt(){when(config.get("ai.tutor.reference.1","")).thenReturn("12");when(mapper.findPreferred(1L,12L)).thenReturn(new OfficialSolutionMapper.OfficialPost(12L,"title","HEAD"+"x".repeat(9000)+"TAIL"));var result=service.resolve(1L,true);assertTrue(result.truncated());assertTrue(result.content().startsWith("HEAD"));assertTrue(result.content().endsWith("TAIL"));verify(stats).add("reference.hit",null,1);}
    @Test void invalidCrossProblemOrPrivateSelectionIsRejected(){assertThrows(com.musealgo.oj.common.exception.BusinessException.class,()->service.select(1L,99L));verifyNoInteractions(command);}
    @Test void deletedSelectionDoesNotSilentlySwitchToAnotherAnswer(){when(config.get("ai.tutor.reference.1","")).thenReturn("12");assertNull(service.resolve(1L,true));verify(mapper).findPreferred(1L,12L);verify(stats).add("reference.miss",null,1);}
}
