package com.musealgo.oj.community.interaction;

import com.musealgo.oj.common.exception.BusinessException;
import com.musealgo.oj.community.error.CommunityErrorCode;

/** A state-changing interaction request. State is supplied by activate/deactivate. */
public record InteractionCommand(
        Long actorId,
        InteractionTargetType targetType,
        Long targetId,
        InteractionAction action) {

    public void validate() {
        if (actorId == null || targetType == null || targetId == null || action == null) {
            throw new BusinessException(CommunityErrorCode.COMMUNITY_INPUT_INVALID, "互动参数不完整");
        }
    }
}
