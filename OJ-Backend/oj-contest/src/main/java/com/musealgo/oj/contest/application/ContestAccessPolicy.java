package com.musealgo.oj.contest.application;

import com.musealgo.oj.common.exception.BusinessException;
import com.musealgo.oj.contest.error.ContestErrorCode;

import com.musealgo.oj.common.utils.SecurityUtils;
import com.musealgo.oj.contest.entity.Contest;

/** Publication checks shared by contest metadata and its public subresources. */
public final class ContestAccessPolicy {
    private ContestAccessPolicy() { }

    public static void requireReadable(Contest contest) {
        if (contest == null || Integer.valueOf(1).equals(contest.getIsDeleted())) {
            throw new BusinessException(ContestErrorCode.CONTEST_NOT_FOUND, "比赛不存在");
        }
        if (!Integer.valueOf(1).equals(contest.getVisible())) {
            SecurityUtils.requireTeacherOrAdmin();
            SecurityUtils.requireOwnerOrAdmin(contest.getAuthorId());
        }
    }
}
