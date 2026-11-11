package com.musealgo.oj.community.content;

import com.musealgo.oj.common.exception.BusinessException;
import com.musealgo.oj.community.entity.Post;
import com.musealgo.oj.community.error.CommunityErrorCode;

import java.util.Set;

/**
 * 内容写侧的字段规则与类型推断。
 * <p>
 * 发布与编辑共用同一套不变量：字段长度、可见性取值、类型/分类组合、班级归属。
 * 规则放在这里而不是服务实现里，是为了能在不启动 Spring 的情况下单测，
 * 也避免 publish / update 两条路径各自演化。
 * </p>
 * <p>
 * <b>分类不是自由文本：</b>公告只能走管理端接口；班级内容的类型由归属决定，
 * 不能让作者通过编辑把它改成普通文章。这些判断如果只放在前端，
 * 一次直接调用接口就能绕过。
 * </p>
 */
public final class PostContentRules {

    public static final int TITLE_MAX = 128;
    public static final int SUMMARY_MAX = 256;
    public static final int SUMMARY_PREVIEW = 160;
    public static final int CONTENT_MAX = 200_000;
    public static final int IMAGES_MAX = 2000;

    private static final Set<String> ARTICLE_CATEGORIES = Set.of("algorithm", "life", "leisure", "contest");
    private static final Set<String> DISCUSSION_CATEGORIES = Set.of("question", "experience", "contest-discussion");

    private PostContentRules() {
    }

    /** 发布：归一字段 → 推断类型 → 终态校验。 */
    public static void applyForPublish(Post post) {
        normalizeFields(post);
        classify(post, null);
        validateFinal(post, null);
    }

    /**
     * 编辑：班级内容锁定类型与分类，其余与发布一致。
     * 班级归属不在可编辑字段里，一旦允许改类型，数据库仍留着 classId，
     * 内容会同时出现在班级列表和讨论/文章列表。
     */
    public static void applyForUpdate(Post post, Post previous) {
        normalizeFields(post);
        if (previous != null && previous.getClassId() != null) {
            post.setType(3);
            post.setCategory(previous.getCategory());
        } else {
            classify(post, previous);
        }
        validateFinal(post, previous);
    }

    private static void normalizeFields(Post post) {
        String title = post.getTitle() == null ? "" : post.getTitle().trim();
        if (title.isEmpty()) throw invalid("标题不能为空");
        if (title.length() > TITLE_MAX) throw invalid("标题不能超过 " + TITLE_MAX + " 个字符");
        post.setTitle(title);

        String content = post.getContent();
        if (content == null || content.isBlank()) throw invalid("正文不能为空");
        if (content.length() > CONTENT_MAX) throw invalid("正文过长");

        String summary = post.getSummary() == null ? "" : post.getSummary().trim();
        if (summary.isEmpty()) summary = deriveSummary(content);
        if (summary.length() > SUMMARY_MAX) throw invalid("摘要不能超过 " + SUMMARY_MAX + " 个字符");
        post.setSummary(summary);

        Integer privacy = post.getPrivacy();
        if (privacy == null) post.setPrivacy(0);
        else if (privacy != 0 && privacy != 1) throw invalid("可见范围不合法");

        String images = post.getImages();
        if (images != null && images.length() > IMAGES_MAX) throw invalid("图片列表过长");

        Integer type = post.getType();
        if (type != null && (type < 0 || type > 3)) throw invalid("内容类型不合法");
    }

    private static void classify(Post post, Post previous) {
        String category = post.getCategory();

        if ("announcement".equals(category)) throw invalid("公告需通过公告管理发布");

        // 班级内容：归属压过一切，类型固定为 3；分类沿用调用方（通常为 question）。
        if (post.getClassId() != null) {
            post.setType(3);
            return;
        }

        if (Integer.valueOf(3).equals(post.getType())) {
            throw invalid("班级内容需从班级入口发布");
        }

        if (post.getProblemId() != null || "solution".equals(category)) {
            post.setType(1);
            post.setCategory("solution");
            return;
        }

        if (post.getType() == null) post.setType(0);

        if (post.getType() == 1) {
            post.setCategory("solution");
            return;
        }

        if (post.getType() == 2) {
            if (category == null || category.isBlank()) {
                post.setCategory("algorithm");
            } else if (!ARTICLE_CATEGORIES.contains(category)) {
                throw invalid("文章分类不合法");
            }
            return;
        }

        if (post.getType() == 3) return;

        // type=0：分类不在问答白名单内的一律归为 question（兼容历史空分类与旧取值）。
        if (category == null || !DISCUSSION_CATEGORIES.contains(category)) {
            post.setCategory("question");
        }
    }

    private static void validateFinal(Post post, Post previous) {
        Integer type = post.getType();
        if (type == null) throw invalid("内容类型不合法");

        if (type == 1 && post.getProblemId() == null) throw invalid("题解必须关联题目");
        if (type == 2 && !ARTICLE_CATEGORIES.contains(post.getCategory())) throw invalid("文章分类不合法");
        if (type == 0 && !DISCUSSION_CATEGORIES.contains(post.getCategory())) throw invalid("讨论分类不合法");

        if (type == 3) {
            boolean hasClassOwner = (post.getClassId() != null) || (previous != null && previous.getClassId() != null);
            if (!hasClassOwner) throw invalid("班级内容需从班级入口发布");
        }
    }

    /**
     * 从正文提取摘要：去掉代码块、图片与链接地址等非展示文本，折叠空白后截断。
     * content 本身不做任何改动。
     */
    public static String deriveSummary(String content) {
        if (content == null || content.isBlank()) return "";
        String text = content
                .replaceAll("(?s)```.*?```", " ")
                .replaceAll("`[^`]*`", " ")
                .replaceAll("!\\[[^\\]]*\\]\\([^)]*\\)", " ")
                .replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("(?m)^\\s{0,3}#{1,6}\\s*", "")
                .replaceAll("(?m)^\\s*>\\s?", "")
                .replaceAll("(?m)^\\s*[-*+]\\s+", "")
                .replaceAll("(?m)^\\s*\\d+\\.\\s+", "")
                .replaceAll("(?m)^\\s*\\|?\\s*:?-{3,}:?.*$", " ")
                .replaceAll("[|*_~]", " ")
                .replaceAll("\\s+", " ")
                .replaceAll("\\s+([，。！？；：、,.!?;:])", "$1")
                .trim();
        if (text.isEmpty()) return "";
        return text.length() <= SUMMARY_PREVIEW ? text : text.substring(0, SUMMARY_PREVIEW) + "…";
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(CommunityErrorCode.COMMUNITY_INPUT_INVALID, message);
    }
}
