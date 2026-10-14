package top.everblue.oj.community.query;

import com.baomidou.mybatisplus.core.metadata.IPage;
import top.everblue.oj.community.entity.vo.PostVO;

import java.util.List;
import java.util.Map;

/** Read model for post lists and fully assembled post views. */
public interface PostQueryService {
    IPage<PostVO> list(Integer page, Integer size, String category, Integer type, List<Integer> types,
                        Long authorId, Long contestId, Long problemId, Long classId, String keyword, List<String> tags, String sort);
    IPage<PostVO> relatedArticles(Long problemId, Integer page, Integer size, String sort);
    IPage<PostVO> authoredBy(Long userId, Integer page, Integer size, String contentType);
    Map<String, Object> detail(Long postId, Long currentUserId);
}
