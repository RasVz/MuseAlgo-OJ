package com.musealgo.oj.teaching.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import com.musealgo.oj.teaching.entity.Courseware;
import com.musealgo.oj.teaching.mapper.CoursewareMapper;
import com.musealgo.oj.teaching.mapper.CoursewareUsageMapper;
import com.musealgo.oj.teaching.service.CoursewareService;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * <p>
 * 课件资源表 服务实现类
 * </p>
 *
 * @author RasVz
 * @since 2025-12-12
 */
@Service
@RequiredArgsConstructor
public class CoursewareServiceImpl extends ServiceImpl<CoursewareMapper, Courseware> implements CoursewareService {

    private final CoursewareUsageMapper coursewareUsageMapper;

    @Override
    public Map<Long, Integer> countClassUses(Collection<Long> coursewareIds) {
        return coursewareUsageMapper.countClassUses(coursewareIds).stream()
                .collect(Collectors.toMap(
                        row -> ((Number) row.get("coursewareId")).longValue(),
                        row -> ((Number) row.get("cnt")).intValue()));
    }
}
