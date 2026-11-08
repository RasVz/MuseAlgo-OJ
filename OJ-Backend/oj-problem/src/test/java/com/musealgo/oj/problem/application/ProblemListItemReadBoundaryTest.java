package com.musealgo.oj.problem.application;
import com.musealgo.oj.problem.entity.vo.ProblemVO;
import com.musealgo.oj.problem.mapper.ProblemListItemMapper;
import com.musealgo.oj.problem.service.impl.ProblemListItemServiceImpl;
import com.musealgo.oj.common.exception.ForbiddenException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ProblemListItemReadBoundaryTest {
 @Test void publicListDoesNotExposeUnreadableProblemButKeepsAssignedPrivateProblem() {
  var mapper=mock(ProblemListItemMapper.class);var access=mock(ProblemReadAccessService.class);
  var hidden=new ProblemVO();hidden.setId(1L);hidden.setTitle("hidden");
  var assigned=new ProblemVO();assigned.setId(2L);assigned.setTitle("assigned private");
  when(mapper.selectProblemsWithStatus(3L,4L)).thenReturn(List.of(hidden,assigned));
  doThrow(new ForbiddenException("private")).when(access).requireReadable(1L);
  var service=new ProblemListItemServiceImpl();ReflectionTestUtils.setField(service,"baseMapper",mapper);
  ReflectionTestUtils.setField(service,"readAccess",access);
  assertEquals(List.of(assigned),service.selectProblemsWithStatus(3L,4L));
 }
 @Test void removedProblemsKeepTheirPositionWithoutExposingOldMetadata() {
  var mapper=mock(ProblemListItemMapper.class);var access=mock(ProblemReadAccessService.class);
  var deleted=new ProblemVO();deleted.setId(8L);deleted.setTitle("synthetic removed title");
  when(mapper.selectProblemsWithStatus(3L,4L)).thenReturn(List.of(deleted));
  doThrow(new com.musealgo.oj.common.exception.BusinessException(com.musealgo.oj.problem.error.ProblemErrorCode.PROBLEM_NOT_FOUND)).when(access).requireReadable(8L);
  var service=new ProblemListItemServiceImpl();ReflectionTestUtils.setField(service,"baseMapper",mapper);ReflectionTestUtils.setField(service,"readAccess",access);
  var result=service.selectProblemsWithStatus(3L,4L);assertEquals(1,result.size());assertNull(result.get(0).getId());assertNull(result.get(0).getTitle());
 }
 @Test void dependencyFailureMustNotBeMisreportedAsAnEmptyList() {
  var mapper=mock(ProblemListItemMapper.class);var access=mock(ProblemReadAccessService.class);var item=new ProblemVO();item.setId(8L);
  when(mapper.selectProblemsWithStatus(3L,4L)).thenReturn(List.of(item));
  var failure=new org.springframework.dao.DataAccessResourceFailureException("synthetic outage");doThrow(failure).when(access).requireReadable(8L);
  var service=new ProblemListItemServiceImpl();ReflectionTestUtils.setField(service,"baseMapper",mapper);ReflectionTestUtils.setField(service,"readAccess",access);
  assertSame(failure,assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,()->service.selectProblemsWithStatus(3L,4L)));
 }
 @Test void publicProblemMetadataFromTheJoinedQueryNeedsNoPerItemLookup() {
  var mapper=mock(ProblemListItemMapper.class);var access=mock(ProblemReadAccessService.class);var item=new ProblemVO();item.setId(8L);item.setIsPublic(true);item.setIsDeleted(0);
  when(mapper.selectProblemsWithStatus(3L,4L)).thenReturn(List.of(item));
  var service=new ProblemListItemServiceImpl();ReflectionTestUtils.setField(service,"baseMapper",mapper);ReflectionTestUtils.setField(service,"readAccess",access);
  assertEquals(List.of(item),service.selectProblemsWithStatus(3L,4L));verifyNoInteractions(access);
 }
}
