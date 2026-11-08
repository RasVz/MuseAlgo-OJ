package com.musealgo.oj.controller;

import com.musealgo.oj.teaching.application.ClassMembershipApplicationService;
import com.musealgo.oj.teaching.entity.ClassCourseware;
import com.musealgo.oj.teaching.entity.Courseware;
import com.musealgo.oj.teaching.mapper.ClassCoursewareMapper;
import com.musealgo.oj.teaching.service.ClassStudentService;
import com.musealgo.oj.teaching.service.CoursewareService;
import com.musealgo.oj.teaching.service.impl.ClassCoursewareServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ClassCoursewareWriteTest {
    private final CoursewareService coursewares=mock(CoursewareService.class);
    private final ClassCoursewareMapper relations=mock(ClassCoursewareMapper.class);
    private final ClassCoursewareServiceImpl service=new ClassCoursewareServiceImpl(coursewares,
            mock(ClassMembershipApplicationService.class),mock(ClassStudentService.class));

    ClassCoursewareWriteTest() {
        ReflectionTestUtils.setField(service,"baseMapper",relations);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(new org.apache.ibatis.builder.MapperBuilderAssistant(
                new com.baomidou.mybatisplus.core.MybatisConfiguration(),"courseware-write-test"),ClassCourseware.class);
    }

    @Test void failedCoursewareInsertCannotCreateABindingWithNullId() {
        when(coursewares.save(any())).thenReturn(false);
        assertThrows(IllegalStateException.class,()->service.addCoursewareToClass(2L,"Synthetic","/synthetic.html",null,null));
        verify(relations,never()).insert(any(ClassCourseware.class));
    }
    @Test void failedBindingInsertIsNotASuccessfulAddition() {
        Courseware courseware=new Courseware();courseware.setId(17L);courseware.setPrivacy(1);
        when(coursewares.getOne(any())).thenReturn(courseware);
        when(relations.selectCount(any())).thenReturn(0L);
        when(relations.insert(any(ClassCourseware.class))).thenReturn(0);
        assertThrows(IllegalStateException.class,()->service.addCoursewareToClass(2L,"Synthetic","/synthetic.html",null,null));
    }
}
