package top.everblue.oj.problem.entity.vo;

import lombok.Getter;
import lombok.Setter;
import top.everblue.oj.problem.entity.ProblemCase;

/**
 * 判题用例视图对象：在 {@link ProblemCase} 基础上附带输入/输出全文（供管理端预览）。
 * <p>
 * 内容超过 {@link #MAX_PREVIEW_CHARS} 时截断，仅影响预览展示，不影响判题
 * （判题直接读取文件系统中的完整内容）。
 * </p>
 */
@Getter
@Setter
public class ProblemCaseVO extends ProblemCase {

    /** 预览内容截断上限（字符数） */
    public static final int MAX_PREVIEW_CHARS = 8 * 1024;

    /** 测试输入全文（超长截断） */
    private String input;

    /** 期望输出全文（超长截断） */
    private String output;

    /** 按截断上限裁剪内容；null 安全 */
    public static String truncate(String content) {
        if (content == null) {
            return "";
        }
        return content.length() > MAX_PREVIEW_CHARS
                ? content.substring(0, MAX_PREVIEW_CHARS) + "\n…（已截断）"
                : content;
    }
}
