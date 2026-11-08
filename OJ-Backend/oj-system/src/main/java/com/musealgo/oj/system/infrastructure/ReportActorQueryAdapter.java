package com.musealgo.oj.system.infrastructure;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import com.musealgo.oj.api.report.ReportActorQuery;
import com.musealgo.oj.api.report.ReportActorSnapshot;
import com.musealgo.oj.system.entity.User;
import com.musealgo.oj.system.mapper.UserMapper;
import com.musealgo.oj.system.service.impl.UserDetailsServiceImpl;

import java.util.Optional;

/** System-owned adapter for the narrow report worker actor query. */
@Service
@RequiredArgsConstructor
public class ReportActorQueryAdapter implements ReportActorQuery {

    private final UserMapper userMapper;
    private final UserDetailsServiceImpl userDetailsService;

    @Override
    public Optional<ReportActorSnapshot> findCurrentActor(Long actorId) {
        if (actorId == null) return Optional.empty();
        User user = userMapper.selectById(actorId);
        if (user == null) return Optional.empty();
        return Optional.of(userDetailsService.reportActorSnapshot(user));
    }
}
