package com.musealgo.oj.contest.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.musealgo.oj.contest.entity.ContestLevel;

import java.util.List;
import java.util.Optional;

/** 比赛等级配置服务（T10-1）。 */
public interface ContestLevelService extends IService<ContestLevel> {

    /** 查询启用中的等级；不存在或已停用返回空。 */
    Optional<ContestLevel> findEnabledByCode(String code);

    /** 列出全部等级（管理端用）。 */
    List<ContestLevel> listAll();
}
