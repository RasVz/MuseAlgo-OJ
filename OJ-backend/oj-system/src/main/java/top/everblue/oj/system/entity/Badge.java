package top.everblue.oj.system.entity;

/**
 * 徽章定义的占位类。
 * <p>
 * 数据库侧的表已经存在（{@code badge} 定义徽章与解锁条件、{@code user_badge} 记录用户持有与佩戴状态，
 * 初始数据见 {@code db/seed.sql}），但 Java 侧<strong>尚未有任何代码引用本类</strong>——
 * 没有对应的 {@code @TableName} 实体、Mapper 或服务，全仓检索只有一个类声明。
 * </p>
 * <p>
 * 因此它现在不会参与任何持久化流程，不能被当成可用的徽章能力使用。
 * 保留这个空类是为了让「徽章」这个待实现的能力有明确的落点：实现时请补齐
 * {@code @TableName("badge")} 与字段映射，并注意 {@code user_badge.is_wearing}
 * 隐含「同一用户同时只能佩戴一个徽章」的业务约束，建表脚本没有用唯一索引强制它，
 * 需要在服务层保证。
 * </p>
 * <p>
 * 在功能真正落地前，请勿在本类里添加「顺手写的」字段或方法——没有调用方会执行它们，
 * 反而会让人误以为徽章功能可用。
 * </p>
 */
public class Badge {
}
