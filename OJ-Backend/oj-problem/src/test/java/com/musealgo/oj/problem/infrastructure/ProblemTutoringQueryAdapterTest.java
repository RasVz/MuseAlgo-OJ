package com.musealgo.oj.problem.infrastructure;
import com.musealgo.oj.api.problem.ProblemAccessQuery;
import com.musealgo.oj.common.exception.ForbiddenException;
import com.musealgo.oj.problem.mapper.*;
import com.musealgo.oj.problem.entity.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
class ProblemTutoringQueryAdapterTest {
    final ProblemAccessQuery access=mock(ProblemAccessQuery.class);
    final ProblemMapper problems=mock(ProblemMapper.class);
    final ProblemSampleMapper samples=mock(ProblemSampleMapper.class);
    final ProblemTutoringQueryAdapter adapter=new ProblemTutoringQueryAdapter(access,problems,samples);
    @Test void deniedAccessReadsNoMaterials(){doThrow(new ForbiddenException("禁止")).when(access).requireReadable(1L);assertThrows(ForbiddenException.class,()->adapter.load(1L));verifyNoInteractions(problems,samples);}
    @Test void contextContainsFormalStatementAndPublicSamplesButNoStd(){
        var p=new Problem();p.setId(1L);p.setTitle("题");p.setContent("正式内容");p.setInputDesc("输入");p.setOutputDesc("输出");p.setHint("n <= 1000");p.setTimeLimit(1000);p.setMemoryLimit(256);p.setStdAnswer("PRIVATE_STD");p.setStdExplanation("PRIVATE_REASON");
        when(problems.selectById(1L)).thenReturn(p);var sample=new ProblemSample();sample.setInput("1 2");sample.setOutput("3");when(samples.selectList(any())).thenReturn(List.of(sample));
        var context=adapter.load(1L);assertEquals("题",context.title());assertTrue(context.statement().contains("n <= 1000"));assertTrue(context.statement().contains("1 2"));assertTrue(context.statement().contains("1000 ms"));assertFalse(context.statement().contains("PRIVATE"));
        var order=inOrder(access,problems);order.verify(access).requireReadable(1L);order.verify(problems).selectById(1L);
    }
    @Test void deletedBetweenPermissionAndReadIsRejected(){var p=new Problem();p.setIsDeleted(1);when(problems.selectById(1L)).thenReturn(p);assertThrows(com.musealgo.oj.common.exception.BusinessException.class,()->adapter.load(1L));verifyNoInteractions(samples);}
}
