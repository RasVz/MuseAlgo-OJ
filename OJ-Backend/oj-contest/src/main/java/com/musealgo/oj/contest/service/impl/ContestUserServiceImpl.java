package com.musealgo.oj.contest.service.impl;

import com.musealgo.oj.common.exception.BusinessException;
import com.musealgo.oj.contest.error.ContestErrorCode;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.musealgo.oj.api.user.UserDisplayInfo;
import com.musealgo.oj.api.user.UserQuery;
import com.musealgo.oj.contest.entity.Contest;
import com.musealgo.oj.contest.application.ContestAccessPolicy;
import com.musealgo.oj.contest.entity.ContestUser;
import com.musealgo.oj.contest.entity.vo.ContestRegistrationVO;
import com.musealgo.oj.contest.mapper.ContestMapper;
import com.musealgo.oj.contest.mapper.ContestUserMapper;
import com.musealgo.oj.contest.service.ContestEligibilityService;
import com.musealgo.oj.contest.service.ContestUserService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 比赛报名服务实现（T10-1）。
 * <p>资格口径：报名时 Rating 快照 + 比赛创建时快照的 Rating 范围；超限者 eligible=0，仍允许报名/参赛，
 * 榜单层再决定其只进全局榜、不计 Rating。</p>
 */
@Service
@RequiredArgsConstructor
public class ContestUserServiceImpl extends ServiceImpl<ContestUserMapper, ContestUser> implements ContestUserService {

    public static final String STATUS_AUTO_APPROVED = "AUTO_APPROVED";
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";

    private final ContestMapper contestMapper;
    private final UserQuery userQuery;
    private final ContestEligibilityService eligibilityService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ContestUser registerContest(Long contestId, Long userId) {
        Contest contest = contestMapper.selectByIdForUpdate(contestId);
        if (contest == null || Integer.valueOf(1).equals(contest.getIsDeleted())) {
            throw new BusinessException(ContestErrorCode.CONTEST_NOT_FOUND, "比赛不存在");
        }
        ContestAccessPolicy.requireReadable(contest);
        ContestUser existing = findRegistration(contestId, userId);
        if (existing != null) {
            throw new BusinessException(ContestErrorCode.CONTEST_ALREADY_REGISTERED, "您已经报名过该比赛啦！");
        }
        Integer rating = userQuery.findRating(userId);
        ContestUser record = new ContestUser();
        record.setContestId(contestId);
        record.setUserId(userId);
        record.setRatingAtRegistration(rating);
        record.setEligible(eligibilityService.isEligible(contest, rating) ? 1 : 0);
        record.setStatus(Integer.valueOf(1).equals(contest.getRegistrationReview())
                ? STATUS_PENDING : STATUS_AUTO_APPROVED);
        if (!this.save(record)) throw new IllegalStateException("报名记录保存失败");
        int updated = contestMapper.update(null, new LambdaUpdateWrapper<Contest>()
                .eq(Contest::getId, contestId)
                .setSql("participant_count = participant_count + 1"));
        if (updated != 1) throw new IllegalStateException("比赛报名计数更新失败");
        return record;
    }

    @Override
    public ContestUser findRegistration(Long contestId, Long userId) {
        if (contestId == null || userId == null) {
            return null;
        }
        return getOne(new LambdaQueryWrapper<ContestUser>()
                .eq(ContestUser::getContestId, contestId)
                .eq(ContestUser::getUserId, userId));
    }

    @Override
    public Page<ContestRegistrationVO> pageRegistrations(Long contestId, int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        Page<ContestUser> raw = page(new Page<>(safePage, safeSize),
                new LambdaQueryWrapper<ContestUser>()
                        .eq(ContestUser::getContestId, contestId)
                        .orderByDesc(ContestUser::getId));
        List<Long> userIds = raw.getRecords().stream().map(ContestUser::getUserId).toList();
        Map<Long, UserDisplayInfo> users = userQuery.findDisplayInfoBatch(userIds);
        Page<ContestRegistrationVO> result = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        result.setRecords(raw.getRecords().stream().map(row -> {
            UserDisplayInfo info = users.get(row.getUserId());
            return ContestRegistrationVO.of(row,
                    info == null ? null : info.username(),
                    info == null ? null : info.nickname());
        }).toList());
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reviewRegistration(Long contestId, Long userId, boolean approve, String reason, Long reviewerId) {
        ContestUser registration = findRegistration(contestId, userId);
        if (registration == null) {
            throw new BusinessException(ContestErrorCode.CONTEST_REGISTRATION_NOT_FOUND, "报名记录不存在");
        }
        if (!STATUS_PENDING.equals(registration.getStatus())) {
            throw new BusinessException(ContestErrorCode.CONTEST_REGISTRATION_NOT_PENDING, "仅待审核的报名可以审核");
        }
        registration.setStatus(approve ? STATUS_APPROVED : STATUS_REJECTED);
        registration.setReviewedBy(reviewerId);
        registration.setReviewTime(LocalDateTime.now());
        registration.setRejectReason(approve ? null : (reason == null ? null : reason.trim()));
        if (!this.update(registration, new LambdaUpdateWrapper<ContestUser>()
                .eq(ContestUser::getId, registration.getId())
                .eq(ContestUser::getStatus, STATUS_PENDING))) {
            throw new BusinessException(ContestErrorCode.CONTEST_REGISTRATION_NOT_PENDING, "报名状态已改变，请刷新后重试");
        }
    }
}
