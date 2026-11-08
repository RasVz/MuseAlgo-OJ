package com.musealgo.oj.common.utils;

import com.musealgo.oj.common.exception.BusinessException;
import com.musealgo.oj.common.exception.CommonErrorCode;
import java.util.Map;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.DateTimeException;
import java.time.format.DateTimeParseException;

/** Explicit parsing at legacy Map request boundaries; internal NumberFormatException is not an API contract. */
public final class RequestValues {
    private RequestValues() { }
    public static Long longValue(Object value, String field) {
        if (value == null) throw new BusinessException(CommonErrorCode.REQUIRED_PARAMETER_MISSING,
                "缺少请求参数：" + field, Map.of("field", field));
        try { return Long.valueOf(value.toString()); }
        catch (NumberFormatException invalid) { throw invalid(field); }
    }
    public static Integer intValue(Object value, String field) {
        if (value == null) throw new BusinessException(CommonErrorCode.REQUIRED_PARAMETER_MISSING,
                "缺少请求参数：" + field, Map.of("field", field));
        try { return Integer.valueOf(value.toString()); }
        catch (NumberFormatException invalid) { throw invalid(field); }
    }

    /** Optional ISO date/time filter; date-only end bounds include that day's final nanosecond. */
    public static LocalDateTime dateTime(String value, String field, boolean endOfDate) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException notDateTime) {
            try {
                LocalDate date = LocalDate.parse(value);
                return endOfDate ? date.atTime(LocalTime.MAX) : date.atStartOfDay();
            } catch (DateTimeException invalidDate) {
                throw invalid(field);
            }
        }
    }
    private static BusinessException invalid(String field) {
        return new BusinessException(CommonErrorCode.PARAMETER_TYPE_MISMATCH,
                "请求参数格式错误：" + field, Map.of("field", field));
    }
}
