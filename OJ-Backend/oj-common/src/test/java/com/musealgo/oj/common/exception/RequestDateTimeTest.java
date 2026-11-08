package com.musealgo.oj.common.exception;

import com.musealgo.oj.common.utils.RequestValues;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;

class RequestDateTimeTest {
    @Test void optionalFiltersRemainAbsent() {
        assertNull(RequestValues.dateTime(null,"start",false));
        assertNull(RequestValues.dateTime("  ","end",true));
    }
    @Test void datesKeepTheirExistingStartAndEndSemantics() {
        assertEquals(LocalDateTime.parse("2026-11-08T00:00:00"),RequestValues.dateTime("2026-11-08","start",false));
        assertEquals(LocalDateTime.parse("2026-11-08T23:59:59.999999999"),RequestValues.dateTime("2026-11-08","end",true));
        assertEquals(LocalDateTime.parse("2026-11-08T12:30:45"),RequestValues.dateTime("2026-11-08T12:30:45","end",true));
    }
    @Test void malformedFilterReturnsOnlyTheFieldName() {
        var error=assertThrows(BusinessException.class,()->RequestValues.dateTime("private-invalid-value","start",false));
        assertEquals(CommonErrorCode.PARAMETER_TYPE_MISMATCH,error.getErrorCode());
        assertEquals("start",error.getDetails().get("field"));
        assertFalse(error.getMessage().contains("private-invalid-value"));
    }
}
