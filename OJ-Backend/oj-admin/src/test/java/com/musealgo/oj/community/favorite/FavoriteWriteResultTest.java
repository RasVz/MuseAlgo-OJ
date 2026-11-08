package com.musealgo.oj.community.favorite;
import com.musealgo.oj.community.entity.UserFavorite;
import com.musealgo.oj.community.service.UserFavoriteService;
import com.musealgo.oj.api.problem.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class FavoriteWriteResultTest {
    @org.junit.jupiter.api.BeforeAll static void metadata() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
            new org.apache.ibatis.builder.MapperBuilderAssistant(new org.apache.ibatis.session.Configuration(), "favorite"), UserFavorite.class);
    }
    @Test void failedStateWriteDoesNotChangeTargetCount() {
        var favorites = mock(UserFavoriteService.class); var counts = mock(ProblemListCommand.class);
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq(Long.class), eq(1L))).thenReturn(java.util.List.of(1L));
        var row = new UserFavorite(); row.setId(2L); row.setStatus(1);
        when(favorites.getOne(any())).thenReturn(row);
        var service = new FavoriteApplicationServiceImpl(favorites, mock(ProblemListQuery.class), counts, jdbc, null, null);
        assertThrows(IllegalStateException.class, () -> service.deactivate(new FavoriteCommand(3L, 1L, FavoriteTargetType.PROBLEM_LIST)));
        verifyNoInteractions(counts);
    }
}
