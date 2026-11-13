package com.musealgo.oj.community.content;

import com.musealgo.oj.common.exception.BusinessException;
import com.musealgo.oj.community.entity.Post;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostContentRulesTest {

    private static Post post(Integer type, String category) {
        Post post = new Post();
        post.setTitle("标题");
        post.setContent("正文");
        post.setType(type);
        post.setCategory(category);
        return post;
    }

    private static String errorOf(Runnable action) {
        BusinessException exception = assertThrows(BusinessException.class, action::run);
        return exception.getMessage();
    }

    @Test
    void articleFillsDefaultCategoryAndKeepsGivenOne() {
        Post blank = post(2, null);
        PostContentRules.applyForPublish(blank);
        assertEquals("algorithm", blank.getCategory());
        assertEquals(2, blank.getType());

        Post life = post(2, "life");
        PostContentRules.applyForPublish(life);
        assertEquals("life", life.getCategory());
    }

    @Test
    void articleRejectsCategoryOutsideWhitelist() {
        assertEquals("文章分类不合法", errorOf(() -> PostContentRules.applyForPublish(post(2, "question"))));
    }

    @Test
    void discussionRewritesUnknownCategoryToQuestion() {
        Post post = post(0, "share");
        PostContentRules.applyForPublish(post);
        assertEquals("question", post.getCategory());
        assertEquals(0, post.getType());
    }

    @Test
    void solutionRequiresProblemAndWinsOverCategory() {
        Post withProblem = post(2, "algorithm");
        withProblem.setProblemId(1001L);
        PostContentRules.applyForPublish(withProblem);
        assertEquals(1, withProblem.getType());
        assertEquals("solution", withProblem.getCategory());

        assertEquals("题解必须关联题目", errorOf(() -> PostContentRules.applyForPublish(post(1, "solution"))));
    }

    @Test
    void announcementAndFakeClassContentAreRejected() {
        assertEquals("公告需通过公告管理发布", errorOf(() -> PostContentRules.applyForPublish(post(0, "announcement"))));
        assertEquals("班级内容需从班级入口发布", errorOf(() -> PostContentRules.applyForPublish(post(3, "question"))));
    }

    @Test
    void fieldLimitsAreEnforced() {
        Post longTitle = post(2, "algorithm");
        longTitle.setTitle("a".repeat(129));
        assertEquals("标题不能超过 128 个字符", errorOf(() -> PostContentRules.applyForPublish(longTitle)));

        Post longSummary = post(2, "algorithm");
        longSummary.setSummary("a".repeat(257));
        assertEquals("摘要不能超过 256 个字符", errorOf(() -> PostContentRules.applyForPublish(longSummary)));

        Post badPrivacy = post(2, "algorithm");
        badPrivacy.setPrivacy(2);
        assertEquals("可见范围不合法", errorOf(() -> PostContentRules.applyForPublish(badPrivacy)));

        Post blankContent = post(2, "algorithm");
        blankContent.setContent("   ");
        assertEquals("正文不能为空", errorOf(() -> PostContentRules.applyForPublish(blankContent)));
    }

    @Test
    void summaryIsDerivedWhenBlank() {
        Post post = post(2, "algorithm");
        post.setSummary("");
        post.setContent("# 标题\n\n这是 **正文**，见 [文档](https://example.com)。\n\n```js\nconst a = 1\n```\n\n![图](a.png)");
        PostContentRules.applyForPublish(post);

        assertEquals("标题 这是 正文，见 文档。", post.getSummary());
    }

    @Test
    void derivedSummaryIsTruncated() {
        String summary = PostContentRules.deriveSummary("字".repeat(200));
        assertEquals(PostContentRules.SUMMARY_PREVIEW + 1, summary.length());
        assertTrue(summary.endsWith("…"));
    }

    @Test
    void updateKeepsClassContentTypeAndCategory() {
        Post previous = post(3, "question");
        previous.setClassId(9L);

        Post patch = post(2, "algorithm");
        PostContentRules.applyForUpdate(patch, previous);

        assertEquals(3, patch.getType());
        assertEquals("question", patch.getCategory());
    }

    @Test
    void updateWithoutClassOwnerCannotUseClassType() {
        Post patch = post(3, "question");
        assertEquals("班级内容需从班级入口发布", errorOf(() -> PostContentRules.applyForUpdate(patch, post(0, "question"))));
    }

    @Test
    void publishWithClassOwnerKeepsClassType() {
        Post post = post(0, "question");
        post.setClassId(9L);
        PostContentRules.applyForPublish(post);
        assertEquals(3, post.getType());
        assertEquals("question", post.getCategory());
    }
}
