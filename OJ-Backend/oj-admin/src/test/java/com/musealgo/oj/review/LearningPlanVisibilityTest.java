package com.musealgo.oj.review;
import com.musealgo.oj.controller.UserController;
import com.musealgo.oj.system.entity.User;
import com.musealgo.oj.system.service.UserService;
import com.musealgo.oj.analytics.application.TagRatingService;
import com.musealgo.oj.api.profile.ProfileQuery;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class LearningPlanVisibilityTest {
 @Test void beginnerFallbackOnlySelectsPublicProblems() {
  var jdbc=mock(JdbcTemplate.class);var users=mock(UserService.class);var ratings=mock(TagRatingService.class);
  var user=new User();user.setId(3L);when(users.getById(3L)).thenReturn(user);
  when(ratings.selectRatingsAll(3L)).thenReturn(new ArrayList<>());
  var controller=new UserController();ReflectionTestUtils.setField(controller,"userService",users);
  ReflectionTestUtils.setField(controller,"jdbcTemplate",jdbc);ReflectionTestUtils.setField(controller,"tagRatingService",ratings);
  ReflectionTestUtils.setField(controller,"profileQuery",mock(ProfileQuery.class));
  org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(user,null,List.of()));
  try {controller.learningPlan(3L);
   verify(jdbc).queryForList(argThat(sql->sql.contains("FROM problem") && sql.contains("is_public = 1")));
  }finally{org.springframework.security.core.context.SecurityContextHolder.clearContext();}
 }
}
