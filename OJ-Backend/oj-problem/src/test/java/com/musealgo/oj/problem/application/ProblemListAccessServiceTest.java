package com.musealgo.oj.problem.application;

import com.musealgo.oj.api.teaching.ClassQuery;
import com.musealgo.oj.problem.entity.ProblemList;
import com.musealgo.oj.problem.mapper.ProblemListMapper;
import com.musealgo.oj.common.exception.ForbiddenException;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProblemListAccessServiceTest {
    private final ClassQuery classes = mock(ClassQuery.class);
    private final ProblemListMapper lists = mock(ProblemListMapper.class);
    private final ProblemListAccessService access = new ProblemListAccessService(lists, classes);

    private ProblemList list(int type, int privacy) {
        var list = new ProblemList(); list.setType((byte)type); list.setPrivacy(privacy);
        list.setCreatorId(1L); list.setClassId(10L); return list;
    }

    @Test void publicPersonalListAllowsAnonymous() { assertTrue(access.readable(list(1,1),null)); }
    @Test void privatePersonalListOnlyAllowsOwner() {
        assertTrue(access.readable(list(1,0),1L));
        assertFalse(access.readable(list(1,0),2L));
        assertFalse(access.readable(list(1,0),null));
    }
    @Test void classListRequiresCurrentMembershipEvenWhenMarkedPublic() {
        when(classes.findAccessibleIds(2L)).thenReturn(List.of(10L),List.of());
        assertTrue(access.readable(list(3,1),2L));
        assertFalse(access.readable(list(3,1),2L));
        assertFalse(access.readable(list(4,1),null));
    }
    @Test void unauthenticatedActorCannotChangeListOrder() {
        when(lists.selectById(1L)).thenReturn(list(1,1));
        assertThrows(ForbiddenException.class,()->access.requireEditable(1L));
    }
}
