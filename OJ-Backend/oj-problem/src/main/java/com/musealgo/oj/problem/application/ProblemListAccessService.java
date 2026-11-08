package com.musealgo.oj.problem.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.musealgo.oj.api.teaching.ClassQuery;
import com.musealgo.oj.common.exception.ForbiddenException;
import com.musealgo.oj.common.utils.SecurityUtils;
import com.musealgo.oj.problem.entity.ProblemList;
import com.musealgo.oj.problem.mapper.ProblemListMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.List;

/** 题单详情、题目列表与目录共用的可见范围。privacy=1公开，0私有；班级题单始终按当前成员关系访问。 */
@Service
@RequiredArgsConstructor
public class ProblemListAccessService {
    private final ProblemListMapper lists;
    private final ClassQuery classes;

    public List<Long> accessibleClassIds() {
        Long actor = SecurityUtils.getUserId();
        return actor == null ? List.of() : classes.findAccessibleIds(actor);
    }

    public boolean readable(ProblemList list, Long actor) {
        if (list == null) return false;
        if (SecurityUtils.isAdmin()) return true;
        if (isClassList(list)) return actor != null && classes.findAccessibleIds(actor).contains(list.getClassId());
        return Integer.valueOf(1).equals(list.getPrivacy()) || actor != null && actor.equals(list.getCreatorId());
    }

    public void requireReadable(Long id) {
        if (!readable(lists.selectById(id), SecurityUtils.getUserId())) throw new ForbiddenException("无权访问该题单");
    }

    public void requireEditable(Long id) {
        ProblemList list = lists.selectById(id);
        Long actor = SecurityUtils.getUserId();
        if (list == null || actor == null || !(actor.equals(list.getCreatorId())
                || SecurityUtils.isAdmin() && Byte.valueOf((byte) 2).equals(list.getType())))
            throw new ForbiddenException("无权修改该题单");
    }

    public void restrict(LambdaQueryWrapper<ProblemList> query) {
        if (SecurityUtils.isAdmin()) return;
        Long actor = SecurityUtils.getUserId();
        List<Long> classIds = accessibleClassIds();
        query.and(w -> {
            w.nested(p -> p.notIn(ProblemList::getType, 3, 4).and(v -> {
                v.eq(ProblemList::getPrivacy, 1);
                if (actor != null) v.or().eq(ProblemList::getCreatorId, actor);
            }));
            if (!classIds.isEmpty()) w.or(c -> c.in(ProblemList::getType, 3, 4).in(ProblemList::getClassId, classIds));
        });
    }

    private static boolean isClassList(ProblemList list) {
        return Byte.valueOf((byte) 3).equals(list.getType()) || Byte.valueOf((byte) 4).equals(list.getType());
    }
}
