package com.musealgo.oj.ai.application;

import com.musealgo.oj.ai.adminai.*;
import com.musealgo.oj.ai.crypto.AiKeyCipher;
import com.musealgo.oj.ai.network.AiEndpointGuard;
import com.musealgo.oj.ai.network.AiEndpointException;
import com.musealgo.oj.common.exception.ErrorResponses;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.PlatformTransactionManager;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Base64;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class AiServiceEnvironmentTest {
    @TempDir Path temporary;

    private AiServiceCatalogService catalog(AiEndpointGuard guard, AiKeyCipher cipher) {
        return new AiServiceCatalogService(null, null, cipher, guard, null, null, mock(PlatformTransactionManager.class));
    }

    private AiServiceAdminDtos.CreateRequest request() {
        var request = new AiServiceAdminDtos.CreateRequest();
        request.setName("test"); request.setModel("test"); request.setApiUrl("https://api.example.com/");
        request.setApiKey("synthetic-key"); return request;
    }

    @Test void missingMasterKeyHasStableDeploymentErrorWithoutDatabaseWrite() {
        var guard = mock(AiEndpointGuard.class);
        when(guard.validate(anyString())).thenReturn(URI.create("https://api.example.com/"));
        var failure = assertThrows(AiAdminException.class, () -> catalog(guard, new AiKeyCipher("")).create(request(), 1L));
        var response = ErrorResponses.failure(failure.getErrorCode(), failure.getMessage());
        assertEquals("AI_MASTER_KEY_NOT_READY", response.getErrorCode()); assertEquals(503, response.getCode());
    }

    @Test void networkFailureAndPolicyRejectionAreDifferentFromInputErrors() {
        for (String code : new String[]{"AI_DNS_UNAVAILABLE", "AI_ENDPOINT_FORBIDDEN"}) {
            var guard = mock(AiEndpointGuard.class);
            when(guard.validate(anyString())).thenThrow(new AiEndpointException(code, code.contains("DNS") ? 503 : 400, "safe"));
            var error = assertThrows(AiAdminException.class, () -> catalog(guard, new AiKeyCipher("")).create(request(), 1L));
            assertEquals(code, error.getErrorCode().key());
        }
        var guard = mock(AiEndpointGuard.class);
        when(guard.validate(anyString())).thenThrow(new IllegalArgumentException("bad URL"));
        var error = assertThrows(AiAdminException.class, () -> catalog(guard, new AiKeyCipher("")).create(request(), 1L));
        assertEquals("AI_SERVICE_INPUT_INVALID", error.getErrorCode().key());
    }

    @Test void readinessActuallyReadsFileAndDoesNotClaimCiphertextIsDecryptable() throws Exception {
        var file = temporary.resolve("key"); var cipher = new AiKeyCipher(file.toString());
        assertFalse(cipher.readiness().ready());
        Files.writeString(file, "invalid"); assertFalse(cipher.readiness().ready());
        Files.writeString(file, Base64.getEncoder().encodeToString(new byte[32]));
        assertTrue(cipher.readiness().ready());
        var error = assertThrows(com.musealgo.oj.ai.crypto.AiKeyException.class, () -> cipher.decrypt("v1:bad:bad", "v1"));
        assertEquals("AI_SERVICE_KEY_UNAVAILABLE", error.errorCode());
    }
}
