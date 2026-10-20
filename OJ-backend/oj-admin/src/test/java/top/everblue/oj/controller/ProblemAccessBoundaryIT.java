package top.everblue.oj.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import top.everblue.oj.api.problem.ProblemAccessQuery;
import top.everblue.oj.api.contest.ContestSubmissionContextResolver;
import top.everblue.oj.common.exception.ForbiddenException;
import top.everblue.oj.system.entity.User;
import top.everblue.oj.problem.entity.*;
import top.everblue.oj.problem.service.ProblemService;
import top.everblue.oj.problem.application.ProblemManagementApplicationService;
import top.everblue.oj.teaching.entity.ClassStudent;
import top.everblue.oj.teaching.service.ClassService;
import top.everblue.oj.teaching.service.ClassStudentService;
import top.everblue.oj.contest.entity.*;
import top.everblue.oj.contest.mapper.*;
import top.everblue.oj.problem.mapper.*;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 私有题真实SQL验证：公开/所有者/班级作业/正式比赛均从同一访问策略判定。 */
@SpringBootTest(properties = {"oj.judge.watchdog.enabled=false", "oj.messaging.rabbitmq-enabled=false"})
class ProblemAccessBoundaryIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired ProblemAccessQuery access;
    @Autowired ProblemManagementApplicationService details;
    @Autowired ContestSubmissionContextResolver resolver;
    @Autowired ProblemService problems;
    @Autowired ClassService classes;
    @Autowired ClassStudentService students;
    @Autowired ProblemListMapper lists;
    @Autowired ProblemListItemMapper items;
    @Autowired ContestMapper contests;
    @Autowired ProblemContestMapper contestProblems;
    Long teacher, student, other;
    Problem problem;
    top.everblue.oj.teaching.entity.Class cls;
    ProblemList list;
    Contest contest;
    @BeforeEach void fixture() {
        teacher = jdbc.queryForObject("SELECT id FROM user WHERE username='teacher01'", Long.class);
        var ids = jdbc.queryForList("SELECT id FROM user WHERE role=1 ORDER BY id LIMIT 2", Long.class);
        student=ids.get(0); other=ids.get(1);
        problem=new Problem(); problem.setTitle("access-it"); problem.setAuthorId(teacher); problem.setIsPublic(false);
        problem.setStdAnswer("secret"); problem.setIsDeleted(0); problems.save(problem);
        cls=new top.everblue.oj.teaching.entity.Class(); cls.setName("access-it"); cls.setTeacherId(teacher); classes.save(cls);
        var member=new ClassStudent(); member.setClassId(cls.getId()); member.setStudentId(student); students.save(member);
        list=new ProblemList(); list.setTitle("access-it"); list.setCreatorId(teacher); list.setClassId(cls.getId()); list.setType((byte)4);
        list.setStartTime(LocalDateTime.now().minusHours(1)); list.setPrivacy(0); lists.insert(list);
        var item=new ProblemListItem(); item.setListId(list.getId()); item.setProblemId(problem.getId()); items.insert(item);
        contest=new Contest(); contest.setTitle("access-it"); contest.setClassId(cls.getId()); contest.setAuthorId(teacher);
        contest.setVisible(1); contest.setDuration(120); contest.setType("Public"); contest.setMode("OI"); contest.setStartTime(LocalDateTime.now().minusHours(1)); contest.setEndTime(LocalDateTime.now().plusHours(1)); contests.insert(contest);
        var cp=new ProblemContest(); cp.setContestId(contest.getId()); cp.setProblemId(problem.getId()); cp.setVisible(1); cp.setIsDeleted(0); contestProblems.insert(cp);
    }
    void login(Long id,String role) { var u=new User();u.setId(id); SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u,null,List.of(new SimpleGrantedAuthority("ROLE_"+role)))); }
    @AfterEach void cleanup() {
        SecurityContextHolder.clearContext();
        if (problem!=null && problem.getId()!=null) jdbc.update("DELETE FROM problem_contest WHERE problem_id=?",problem.getId());
        if(contest!=null && contest.getId()!=null) jdbc.update("DELETE FROM contest WHERE id=?",contest.getId());
        if(list!=null && list.getId()!=null) { jdbc.update("DELETE FROM problem_list_item WHERE list_id=?",list.getId());jdbc.update("DELETE FROM problem_list WHERE id=?",list.getId()); }
        if(cls!=null && cls.getId()!=null) { jdbc.update("DELETE FROM class_student WHERE class_id=?",cls.getId()); jdbc.update("DELETE FROM class WHERE id=?",cls.getId()); }
        if(problem!=null && problem.getId()!=null) jdbc.update("DELETE FROM problem WHERE id=?",problem.getId());
    }
    @Test void privateClassProblemIsReadableOnlyByAuthorizedActors() {
        assertThrows(ForbiddenException.class,()->details.getDetail(problem.getId()));
        login(other,"STUDENT"); assertThrows(ForbiddenException.class,()->details.getDetail(problem.getId()));
        login(student,"STUDENT"); assertNull(details.getDetail(problem.getId()).getStdAnswer());
        assertThrows(ForbiddenException.class,()->access.requirePracticeSubmission(problem.getId()));
        assertNotNull(resolver.resolve(contest.getId(),problem.getId(),student));
        login(other,"STUDENT"); assertThrows(ForbiddenException.class,()->resolver.resolve(contest.getId(),problem.getId(),other));
        jdbc.update("UPDATE problem SET is_public=1 WHERE id=?",problem.getId());
        login(other,"STUDENT"); assertDoesNotThrow(()->access.requirePracticeSubmission(problem.getId()), "其他班级比赛不应阻断公开题日常练习");
        login(teacher,"TEACHER"); assertDoesNotThrow(()->details.getDetail(problem.getId()));
    }
    @Test void futureAssignmentAndFutureContestDoNotGrantEarlyAccess() {
        jdbc.update("UPDATE problem_list SET start_time=DATE_ADD(NOW(),INTERVAL 1 DAY) WHERE id=?",list.getId());
        jdbc.update("UPDATE contest SET start_time=DATE_ADD(NOW(),INTERVAL 1 DAY),end_time=DATE_ADD(NOW(),INTERVAL 2 DAY) WHERE id=?",contest.getId());
        login(student,"STUDENT"); assertThrows(ForbiddenException.class,()->details.getDetail(problem.getId()));
        jdbc.update("UPDATE problem SET is_public=1 WHERE id=?",problem.getId());
        SecurityContextHolder.clearContext(); assertDoesNotThrow(()->details.getDetail(problem.getId()));
    }
}
