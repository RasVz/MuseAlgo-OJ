package top.everblue.oj.problem;

import org.junit.jupiter.api.Test;
import top.everblue.oj.api.user.UserQuery;
import top.everblue.oj.problem.controller.ProblemListController;
import top.everblue.oj.problem.entity.ProblemList;
import top.everblue.oj.problem.service.ProblemListService;
import java.util.Optional;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class ProblemListContractTest {
    @Test void homeworkDetailPreservesAssignmentTypeForTheBrowser() {
        var lists=mock(ProblemListService.class);var users=mock(UserQuery.class);
        var homework=new ProblemList();homework.setId(4L);homework.setCreatorId(2L);homework.setType((byte)4);
        when(lists.getById(4L)).thenReturn(homework);when(users.findDisplayInfo(2L)).thenReturn(Optional.empty());
        var result=new ProblemListController(users,lists).getDetail(4L).getData();
        assertEquals((byte)4,result.getType());
    }
}
