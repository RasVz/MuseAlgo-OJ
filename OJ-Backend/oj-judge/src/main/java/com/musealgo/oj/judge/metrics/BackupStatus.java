package com.musealgo.oj.judge.metrics;

/**
 * 备份新鲜度/完整性检查结果（OPS-02 监控收口）。
 *
 * <p><b>关键语义：未配置备份监控不得显示健康。</b>{@code configured=false} 时
 * {@code healthy} 恒为 {@code false}，由 {@link OpsSnapshot#healthStatus(long, long)}
 * 汇总成整体不健康；任何调用方都不得把「没配」解释成「健康」。</p>
 *
 * @param configured  是否配置了备份根目录（{@code oj.monitoring.backup.root}）
 * @param healthy     最新备份是否新鲜且通过清单/校验和检查（未配置恒 false）
 * @param ageSeconds  最新备份年龄（秒）；未配置、无备份或损坏为 -1
 * @param corrupt     最新备份清单非法、四件文件缺失或 sha256 不匹配
 * @param lastCheckOk 最近一次检查本身是否完成（IO/权限失败为 false）
 */
public record BackupStatus(boolean configured, boolean healthy, long ageSeconds, boolean corrupt, boolean lastCheckOk) {

    /** 未配置备份根目录：configured=false 且 healthy=false（绝不放行为健康）。 */
    public static BackupStatus notConfigured() {
        return new BackupStatus(false, false, -1L, false, true);
    }

    /** 已配置但未找到任何备份清单（等价于 ops.py check 的 "no backup manifest found"）。 */
    public static BackupStatus missing() {
        return new BackupStatus(true, false, -1L, false, true);
    }

    /** 最新备份损坏（等价于 ops.py check 的 "latest backup is corrupt"）。 */
    public static BackupStatus corrupted() {
        return new BackupStatus(true, false, -1L, true, true);
    }

    /** 检查自身失败（IO/权限/路径异常），不代表备份健康。 */
    public static BackupStatus checkFailed() {
        return new BackupStatus(true, false, -1L, false, false);
    }

    /** 检查完成且备份可用；healthy 取决于新鲜度阈值。 */
    public static BackupStatus ok(long ageSeconds, boolean healthy) {
        return new BackupStatus(true, healthy, ageSeconds, false, true);
    }
}
