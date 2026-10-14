package top.everblue.oj.api.system;

/**
 * 系统配置只读查询契约 — 跨模块读取 {@code system_config} 配置的唯一入口。
 * <p>
 * 实现由 oj-system 模块提供（数据归属方，带 5 秒内存缓存），
 * oj-ai / oj-problem 等模块禁止直接注入 SystemConfigService。
 * </p>
 */
public interface SystemConfigQuery {

    /** 读取字符串配置，未配置时返回 defaultValue。 */
    String get(String key, String defaultValue);

    /** 读取整型配置，未配置或非法时返回 defaultValue。 */
    int getInt(String key, int defaultValue);

    /** 读取布尔配置，未配置或非法时返回 defaultValue。 */
    boolean getBool(String key, boolean defaultValue);
}
