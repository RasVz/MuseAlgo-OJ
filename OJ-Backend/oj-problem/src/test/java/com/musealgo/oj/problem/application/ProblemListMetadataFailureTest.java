package com.musealgo.oj.problem.application;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.musealgo.oj.problem.service.ProblemService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class ProblemListMetadataFailureTest {
 @Test void databaseFailureRetainsItsCauseAndClassification() {
  var jdbc=mock(JdbcTemplate.class);var failure=new DataAccessResourceFailureException("synthetic outage");
  when(jdbc.queryForList("SHOW COLUMNS FROM problem")).thenThrow(failure);
  var service=new ProblemListQueryApplicationService(mock(ProblemService.class),new ObjectMapper(),jdbc);
  assertSame(failure,assertThrows(DataAccessResourceFailureException.class,()->service.adminList(1,10,null,null,null,null,null,null,null)));
 }
}
