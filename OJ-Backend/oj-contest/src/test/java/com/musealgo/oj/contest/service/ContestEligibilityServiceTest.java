package com.musealgo.oj.contest.service;

import org.junit.jupiter.api.Test;
import com.musealgo.oj.contest.entity.Contest;

import static org.junit.jupiter.api.Assertions.*;

/** T10-1 资格口径单测：下限含、上限不含、新用户合法、班级/娱乐赛不设限。 */
class ContestEligibilityServiceTest {

    private final ContestEligibilityService service = new ContestEligibilityService();

    private Contest contest(String category, Long classId, Integer min, Integer max) {
        Contest c = new Contest();
        c.setCategory(category);
        c.setClassId(classId);
        c.setRatingMin(min);
        c.setRatingMax(max);
        return c;
    }

    @Test
    void formalContestUsesInclusiveMinAndExclusiveMax() {
        Contest c = contest("FORMAL", null, 1000, 1800);
        assertTrue(service.isEligible(c, 1000));
        assertTrue(service.isEligible(c, 1799));
        assertFalse(service.isEligible(c, 1800));
        assertFalse(service.isEligible(c, 999));
    }

    @Test
    void nullBoundsMeanUnlimited() {
        Contest c = contest("FORMAL", null, null, null);
        assertTrue(service.isEligible(c, 9999));
        assertTrue(service.isEligible(c, null));
    }

    @Test
    void newUserOrMissingRatingIsEligible() {
        Contest c = contest("FORMAL", null, 1000, 1800);
        assertTrue(service.isEligible(c, null));
        assertTrue(service.isEligible(c, 0));
    }

    @Test
    void classContestHasNoRatingRestriction() {
        Contest c = contest("FORMAL", 5L, 1000, 1800);
        assertTrue(service.isEligible(c, 2500));
    }

    @Test
    void entertainmentContestDoesNotApplyRating() {
        Contest c = contest("ENTERTAINMENT", null, 1000, 1800);
        assertTrue(service.isEligible(c, 2500));
        assertFalse(service.isRatingApplicable(c));
    }
}
