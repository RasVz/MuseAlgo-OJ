package com.musealgo.oj.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.musealgo.oj.common.exception.CommonErrorCode;
import com.musealgo.oj.common.exception.BusinessException;
import com.musealgo.oj.dto.AdminClassMemberCount;
import com.musealgo.oj.dto.AdminClassSummary;
import com.musealgo.oj.mapper.AdminClassDirectoryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Validates directory query inputs and delegates the paged read projection. */
@Service
@RequiredArgsConstructor
public class AdminClassDirectoryService {

    private static final int MAX_PAGE_SIZE = 200;
    private static final int MAX_KEYWORD_LENGTH = 100;

    private final AdminClassDirectoryMapper mapper;

    public IPage<AdminClassSummary> list(
            Integer page,
            Integer size,
            String keyword,
            String memberStatus,
            String sort) {
        if (page == null || page < 1) {
            throw invalid("page 必须大于等于 1");
        }
        if (size == null || size < 1 || size > MAX_PAGE_SIZE) {
            throw invalid("size 必须在 1 到 200 之间");
        }

        String normalizedKeyword = keyword == null ? null : keyword.strip();
        if (normalizedKeyword != null && normalizedKeyword.length() > MAX_KEYWORD_LENGTH) {
            throw invalid("keyword 长度不能超过 100 个字符");
        }
        if (normalizedKeyword != null && normalizedKeyword.isEmpty()) {
            normalizedKeyword = null;
        }

        String normalizedMemberStatus = memberStatus == null ? "all" : memberStatus;
        if (!"all".equals(normalizedMemberStatus)
                && !"empty".equals(normalizedMemberStatus)
                && !"hasMembers".equals(normalizedMemberStatus)) {
            throw invalid("memberStatus 仅支持 all、empty、hasMembers");
        }

        String normalizedSort = sort == null ? "newest" : sort;
        if (!"newest".equals(normalizedSort)
                && !"oldest".equals(normalizedSort)
                && !"name".equals(normalizedSort)) {
            throw invalid("sort 仅支持 newest、oldest、name");
        }

        Long classId = parseId(normalizedKeyword);
        IPage<AdminClassSummary> result = mapper.selectPage(new Page<>(page, size), normalizedKeyword, classId,
                normalizedMemberStatus, normalizedSort);
        List<AdminClassSummary> records = result.getRecords();
        if (!records.isEmpty()) {
            List<Long> classIds = records.stream().map(AdminClassSummary::getId).toList();
            Map<Long, Long> counts = mapper.selectStudentCounts(classIds).stream()
                    .collect(Collectors.toMap(AdminClassMemberCount::getClassId, AdminClassMemberCount::getStudentCount));
            records.forEach(row -> row.setStudentCount(counts.getOrDefault(row.getId(), 0L)));
        }
        return result;
    }

    private static Long parseId(String keyword) {
        if (keyword == null) {
            return null;
        }
        try {
            return Long.parseLong(keyword);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(CommonErrorCode.INVALID_ARGUMENT, message);
    }
}
