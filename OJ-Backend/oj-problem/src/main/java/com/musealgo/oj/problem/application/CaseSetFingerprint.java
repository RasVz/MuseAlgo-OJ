package com.musealgo.oj.problem.application;

import com.musealgo.oj.problem.entity.ProblemCase;

import java.util.List;
import java.util.StringJoiner;

/**
 * 用例集合指纹：用于判断「一次长耗时操作期间用例是否被改动」。
 * <p>
 * 只取用例主键、顺序、分值与子任务号，不读文件内容：
 * 现有全部写入入口（ZIP 导入 / 批量覆盖 / 追加 / 删除 / 清空）都会新增、删除用例行，
 * 追加还会改写既有行的分值与顺序，因此行级标识足以覆盖本仓库的所有变更路径。
 * 直接改文件系统内容不属于受支持的入口，不在指纹覆盖范围。
 * </p>
 */
final class CaseSetFingerprint {

    private CaseSetFingerprint() {
    }

    static String of(List<ProblemCase> cases) {
        if (cases == null || cases.isEmpty()) {
            return "";
        }
        StringJoiner joiner = new StringJoiner("|");
        for (ProblemCase one : cases) {
            joiner.add(one.getId() + ":" + one.getCaseOrder() + ":" + one.getScore()
                    + ":" + one.getSubtaskNo());
        }
        return joiner.toString();
    }
}
