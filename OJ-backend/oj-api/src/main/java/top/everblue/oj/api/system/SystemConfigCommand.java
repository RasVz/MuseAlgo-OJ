package top.everblue.oj.api.system;

/**
 * 系统配置写契约 — 跨模块修改 {@code system_config} 配置的唯一入口。
 * <p>
 * 实现由 oj-system 模块提供（数据归属方，写后立即失效缓存），
 * oj-ai 管理台等模块禁止直接注入 SystemConfigService。
 * </p>
 */
public interface SystemConfigCommand {

    /** 写入配置（value 为 null 时存空串），写后立即失效缓存，秒级生效。 */
    void set(String key, String value);
}
