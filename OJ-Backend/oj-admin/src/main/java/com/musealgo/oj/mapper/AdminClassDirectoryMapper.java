package com.musealgo.oj.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.musealgo.oj.dto.AdminClassMemberCount;
import com.musealgo.oj.dto.AdminClassSummary;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** Persistence projection for the administrator-only class directory. */
public interface AdminClassDirectoryMapper {

    IPage<AdminClassSummary> selectPage(
            Page<?> page,
            @Param("keyword") String keyword,
            @Param("classId") Long classId,
            @Param("memberStatus") String memberStatus,
            @Param("sort") String sort);

    List<AdminClassMemberCount> selectStudentCounts(@Param("classIds") List<Long> classIds);
}
