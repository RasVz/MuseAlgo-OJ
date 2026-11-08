package com.musealgo.oj.problem;

import org.junit.jupiter.api.Test;
import com.musealgo.oj.api.user.UserQuery;
import com.musealgo.oj.problem.controller.ProblemListController;
import com.musealgo.oj.problem.entity.ProblemList;
import com.musealgo.oj.problem.service.ProblemListService;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class ProblemListContractTest {
    @Test void homeworkDetailPreservesAssignmentTypeForTheBrowser() {
        var lists=mock(ProblemListService.class);var users=mock(UserQuery.class);
        var homework=new ProblemList();homework.setId(4L);homework.setCreatorId(2L);homework.setType((byte)4);
        when(lists.getById(4L)).thenReturn(homework);when(users.findDisplayInfo(2L)).thenReturn(Optional.empty());
        var result=new ProblemListController(mock(com.musealgo.oj.problem.application.ProblemListAccessService.class), mock(com.musealgo.oj.api.teaching.ClassQuery.class),users,lists).getDetail(4L).getData();
        assertEquals((byte)4,result.getType());
    }
}
