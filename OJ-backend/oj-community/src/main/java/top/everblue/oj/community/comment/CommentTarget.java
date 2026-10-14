package top.everblue.oj.community.comment;

/** Identifies the discussion space a comment belongs to. */
/**
 * 评论所属的讨论区标识（对象 ID + 对象类型）。
 * <p>
 * 用 record 而非两个散落的参数，是为了在方法签名里把「查哪张表的评论」这件事
 * 表达完整——传 {@code (id, type)} 容易被误读成两个不相关的参数。
 * </p>
 */
public record CommentTarget(Long id, Integer type) {
    /**
     * 校验标识完整性。
     * <p>
     * 只查非空、不查取值范围：{@code type} 是否是已支持的类型由
     * {@link CommentTargetHandler} 注册表判定，那里有唯一权威的列表。
     * 在此重复维护一份范围，两处一旦不同步就会出现「校验通过但没有处理器」。
     * </p>
     *
     * @throws IllegalArgumentException ID 或类型为 {@code null}
     */
    public void validate() {
        if (id == null || type == null) {
            throw new IllegalArgumentException("必须指定评论对象");
        }
    }
}
