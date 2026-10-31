package com.musealgo.oj.contest.service;

import org.springframework.stereotype.Service;
import com.musealgo.oj.contest.entity.Contest;

/**
 * 比赛资格判定（T10-1）。
 * <p>下限 Rating 为“大于等于”，上限为“小于”；NULL 表示该侧不限；Rating 以报名时快照为准；
 * 新用户/无 Rating 视为合法；班级比赛不设限制；娱乐赛不计算 Rating。</p>
 */
@Service
public class ContestEligibilityService {

    public static final String CATEGORY_FORMAL = "FORMAL";
    public static final String CATEGORY_ENTERTAINMENT = "ENTERTAINMENT";

    public boolean isRatingApplicable(Contest contest) {
        return contest != null && !CATEGORY_ENTERTAINMENT.equalsIgnoreCase(contest.getCategory());
    }

    /** 返回 false 只表示“打星号”，报名本身仍允许。 */
    public boolean isEligible(Contest contest, Integer ratingAtRegistration) {
        if (contest == null) {
            return false;
        }
        if (CATEGORY_ENTERTAINMENT.equalsIgnoreCase(contest.getCategory()) || contest.getClassId() != null) {
            return true;
        }
        if (ratingAtRegistration == null || ratingAtRegistration <= 0) {
            return true;
        }
        Integer min = contest.getRatingMin();
        Integer max = contest.getRatingMax();
        if (min != null && ratingAtRegistration < min) {
            return false;
        }
        return max == null || ratingAtRegistration < max;
    }
}
