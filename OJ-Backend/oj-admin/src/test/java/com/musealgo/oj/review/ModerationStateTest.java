package com.musealgo.oj.review;
import com.musealgo.oj.community.controller.CommunityGovernanceController;
import com.musealgo.oj.community.service.*;
import com.musealgo.oj.common.utils.SecurityUtils;
import com.musealgo.oj.common.exception.BusinessException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class ModerationStateTest {
 @Test void settledReportCannotBeResolvedAgainOrNotifyTwice() {
  var jdbc=mock(JdbcTemplate.class);var notifications=mock(NotificationService.class);
  when(jdbc.queryForList(anyString(),eq(1L))).thenReturn(List.of(Map.of("postId",2L)));
  var controller=new CommunityGovernanceController(null,jdbc,mock(PostService.class),null,notifications);
  var user=new com.musealgo.oj.system.entity.User();user.setId(3L);
  org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
   new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(user,null,List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
  try {
   var error=assertThrows(BusinessException.class,()->controller.resolveReport(1L,Map.of("hide",true)));
   assertEquals(409,error.getErrorCode().status());verifyNoInteractions(notifications);
  }finally {org.springframework.security.core.context.SecurityContextHolder.clearContext();}
 }
}
