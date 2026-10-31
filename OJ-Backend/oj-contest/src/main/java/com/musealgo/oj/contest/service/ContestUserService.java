package com.musealgo.oj.contest.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.IService;
import com.musealgo.oj.contest.entity.ContestUser;
import com.musealgo.oj.contest.entity.vo.ContestRegistrationVO;

/**
 * 比赛报名服务（T10-1 起包含报名审核与资格快照）。
 * <p>职责：报名（快照报名时 Rating、计算资格、按比赛配置决定是否待审核）、管理端审核与列表；
 * 成绩与榜单仍在 ContestRankService。</p>
 */
public interface ContestUserService extends IService<ContestUser> {

    /**
     * 报名比赛：保存报名时 Rating 与资格；需要审核的比赛状态为 PENDING，否则 AUTO_APPROVED。
     * 超限者仍可报名（eligible=0，打星号），不得因资格不符拒绝报名。
     */
    ContestUser registerContest(Long contestId, Long userId);

    /** 查询某用户在某场比赛的报名记录；未报名返回 null。 */
    ContestUser findRegistration(Long contestId, Long userId);

    /** 管理端分页查看报名记录（含用户展示信息）。 */
    Page<ContestRegistrationVO> pageRegistrations(Long contestId, int page, int size);

    /** 审核报名：approve=true 通过，false 拒绝并记录原因；仅允许审核 PENDING 记录。 */
    void reviewRegistration(Long contestId, Long userId, boolean approve, String reason, Long reviewerId);
}
