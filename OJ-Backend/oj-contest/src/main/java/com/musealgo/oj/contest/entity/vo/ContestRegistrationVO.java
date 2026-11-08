package com.musealgo.oj.contest.entity.vo;

import com.musealgo.oj.contest.entity.ContestUser;

import java.time.LocalDateTime;

/** 管理端报名记录视图（T10-1）。 */
public record ContestRegistrationVO(
        Long id,
        Long userId,
        String username,
        String nickname,
        String status,
        Integer ratingAtRegistration,
        Integer eligible,
        Long reviewedBy,
        LocalDateTime reviewTime,
        String rejectReason,
        LocalDateTime createTime
) {
    public static ContestRegistrationVO of(ContestUser row, String username, String nickname) {
        return new ContestRegistrationVO(row.getId(), row.getUserId(), username, nickname,
                row.getStatus(), row.getRatingAtRegistration(), row.getEligible(),
                row.getReviewedBy(), row.getReviewTime(), row.getRejectReason(), row.getCreateTime());
    }
}
