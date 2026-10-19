package top.everblue.oj.common.exception;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class GlobalExceptionHandlerTest {
    @Test void internalExceptionDetailsStayInLogs() {
        var result = new GlobalExceptionHandler().handleException(new RuntimeException("SELECT private_password FROM secret; /private/server.env"));
        assertFalse(result.getMessage().contains("SELECT"));
        assertFalse(result.getMessage().contains("server.env"));
    }
}
