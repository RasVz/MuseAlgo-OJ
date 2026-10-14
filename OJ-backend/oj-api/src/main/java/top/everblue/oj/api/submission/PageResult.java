package top.everblue.oj.api.submission;

import java.util.List;

/** 跨模块查询契约统一分页返回体（与框架无关，避免泄漏 MyBatis-Plus 分页类型）。 */
public record PageResult<T>(long page, long size, long total, List<T> records) {
}
