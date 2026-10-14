package top.everblue.oj.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.everblue.oj.ai.mapper.OfficialSolutionMapper;
import top.everblue.oj.ai.mapper.RagKnowledgeMapper;
import top.everblue.oj.ai.mapper.RagKnowledgeMapper.CoursewareRow;
import top.everblue.oj.ai.mapper.RagKnowledgeMapper.RelatedProblemRow;

import java.util.List;

/**
 * RAG 组装层：多源检索 → 逐源截断 → 按来源标注拼成【参考资料】。
 * <p>
 * 来源标注很重要：模型知道每条资料出处，才能正确引用、避免混淆。
 * 每一源独立设上限（防止单一长文撑爆上下文），总预算随来源数量增长，
 * 后续可加全局预算做更精细的配额。
 */
@Slf4j
@Service
public class RagContextService {

    private static final int SOLUTION_MAX_CHARS = 3000;
    private static final int RELATED_HINT_MAX_CHARS = 300;
    private static final int COURSEWARE_MAX_CHARS = 2000;
    private static final int RELATED_LIMIT = 3;
    private static final int COURSEWARE_LIMIT = 2;

    private final OfficialSolutionMapper officialSolutionMapper;
    private final RagKnowledgeMapper ragKnowledgeMapper;

    public RagContextService(OfficialSolutionMapper officialSolutionMapper,
                             RagKnowledgeMapper ragKnowledgeMapper) {
        this.officialSolutionMapper = officialSolutionMapper;
        this.ragKnowledgeMapper = ragKnowledgeMapper;
    }

    /** 拼装多源参考资料；没有任何命中时返回 null（提示词里显示"暂无"） */
    public String buildReference(Long problemId) {
        if (problemId == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();

        // 来源 1：官方题解 —— problem_id 精确命中
        String solution = officialSolutionMapper.findOfficialSolutionByProblemId(problemId);
        if (solution != null && !solution.isBlank()) {
            sb.append("[官方题解]\n")
                    .append(truncate(solution, SOLUTION_MAX_CHARS))
                    .append("\n\n");
        }

        // 来源 2：相关题目 —— 共享标签 + 难度接近
        Integer difficulty = ragKnowledgeMapper.findDifficultyByProblemId(problemId);
        if (difficulty != null) {
            List<RelatedProblemRow> related =
                    ragKnowledgeMapper.findRelatedProblems(problemId, difficulty, RELATED_LIMIT);
            if (!related.isEmpty()) {
                sb.append("[相关题目]\n");
                for (RelatedProblemRow row : related) {
                    String hint = row.getHint() == null || row.getHint().isBlank()
                            ? "暂无提示" : row.getHint();
                    sb.append("- P").append(row.getId()).append(" ").append(row.getTitle())
                            .append("（难度 ").append(row.getDifficulty())
                            .append("，标签 ").append(row.getTags() == null ? "无" : row.getTags())
                            .append("）\n")
                            .append("  提示：").append(truncate(hint, RELATED_HINT_MAX_CHARS))
                            .append("\n");
                }
                sb.append("\n");
            }
        }

        // 来源 3：课件 —— 同题单优先，其次标签名关键词
        List<String> keywords = ragKnowledgeMapper.findTagNamesByProblemId(problemId);
        List<CoursewareRow> coursewares =
                ragKnowledgeMapper.findCourseware(problemId, keywords, COURSEWARE_LIMIT);
        if (!coursewares.isEmpty()) {
            sb.append("[课件]\n");
            for (CoursewareRow cw : coursewares) {
                String content = cw.getContent() == null ? "" : cw.getContent();
                sb.append("- 《").append(cw.getTitle()).append("》\n")
                        .append(truncate(content, COURSEWARE_MAX_CHARS))
                        .append("\n\n");
            }
        }

        String reference = sb.toString().trim();
        return reference.isEmpty() ? null : reference;
    }

    private static String truncate(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "\n【内容过长已截断】";
    }
}
