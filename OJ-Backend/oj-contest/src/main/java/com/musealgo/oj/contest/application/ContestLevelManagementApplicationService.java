package com.musealgo.oj.contest.application;

import com.musealgo.oj.common.exception.BusinessException;
import com.musealgo.oj.contest.error.ContestErrorCode;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.musealgo.oj.common.utils.SecurityUtils;
import com.musealgo.oj.contest.entity.ContestLevel;
import com.musealgo.oj.contest.mapper.ContestLevelMapper;
import java.time.LocalDateTime;

/** 等级配置写入口：白名单字段与显式 NULL 更新，已有比赛的资格快照不追溯。 */
@Service
@RequiredArgsConstructor
public class ContestLevelManagementApplicationService {
    private final ContestLevelMapper mapper;

    @Transactional(rollbackFor = Exception.class)
    public ContestLevel create(ContestLevel command) {
        if (!SecurityUtils.isAdmin()) throw new com.musealgo.oj.common.exception.ForbiddenException("需要管理员权限");
        validate(command);
        ContestLevel level = new ContestLevel();
        level.setDescription(command.getDescription());
        level.setCode(command.getCode().trim()); level.setName(command.getName().trim());
        level.setMinRating(command.getMinRating()); level.setMaxRating(command.getMaxRating());
        level.setEnabled(command.getEnabled() == null || command.getEnabled());
        level.setSortOrder(command.getSortOrder() == null ? 0 : command.getSortOrder());
        level.setCreateTime(LocalDateTime.now()); level.setUpdateTime(LocalDateTime.now());
        mapper.insert(level);
        return level;
    }

    @Transactional(rollbackFor = Exception.class)
    public ContestLevel update(Long id, ContestLevel command) {
        if (!SecurityUtils.isAdmin()) throw new com.musealgo.oj.common.exception.ForbiddenException("需要管理员权限");
        validate(command);
        ContestLevel old = mapper.selectById(id);
        if (old == null) throw new BusinessException(ContestErrorCode.CONTEST_LEVEL_NOT_FOUND, "等级不存在");
        // MyBatis 默认忽略 NULL，清空上下限必须用显式 SET；创建时间不接受客户端覆盖。
        mapper.update(null, new LambdaUpdateWrapper<ContestLevel>().eq(ContestLevel::getId, id)
                .set(ContestLevel::getCode, command.getCode().trim()).set(ContestLevel::getName, command.getName().trim())
                .set(ContestLevel::getDescription, command.getDescription())
                .set(ContestLevel::getMinRating, command.getMinRating()).set(ContestLevel::getMaxRating, command.getMaxRating())
                .set(ContestLevel::getEnabled, command.getEnabled() == null ? old.getEnabled() : command.getEnabled())
                .set(ContestLevel::getSortOrder, command.getSortOrder() == null ? old.getSortOrder() : command.getSortOrder())
                .set(ContestLevel::getUpdateTime, LocalDateTime.now()));
        return mapper.selectById(id);
    }

    private void validate(ContestLevel command) {
        if (command == null || command.getCode() == null || command.getCode().isBlank()
                || command.getName() == null || command.getName().isBlank())
            throw new BusinessException(ContestErrorCode.CONTEST_INPUT_INVALID, "等级编码与名称不能为空");
        if (command.getCode().trim().length() > 32 || command.getName().trim().length() > 64
                || command.getDescription() != null && command.getDescription().length() > 255)
            throw new BusinessException(ContestErrorCode.CONTEST_INPUT_INVALID, "等级编码/名称/说明超出长度限制");
        if (command.getMinRating() != null && command.getMaxRating() != null
                && command.getMinRating() >= command.getMaxRating())
            throw new BusinessException(ContestErrorCode.CONTEST_INPUT_INVALID, "Rating 下限必须小于上限（上限为不含）");
    }
}
