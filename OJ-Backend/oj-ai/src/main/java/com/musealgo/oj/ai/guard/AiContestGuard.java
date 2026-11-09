package com.musealgo.oj.ai.guard;

import org.springframework.stereotype.Service;

/** 按教学请求/真实提交的比赛场景判断；普通练习不按题目或参赛账号全局封禁。 */
@Service
public class AiContestGuard {
    public boolean shouldBlock(Long contestId) { return contestId != null; }
}
