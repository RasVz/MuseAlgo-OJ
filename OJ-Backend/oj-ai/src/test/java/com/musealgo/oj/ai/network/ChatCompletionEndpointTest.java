package com.musealgo.oj.ai.network;

import org.junit.jupiter.api.Test;
import java.net.InetAddress;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ChatCompletionEndpointTest {
    @Test void officialBaseUrlsBecomeCompleteEndpoints() {
        for (String input : List.of("https://api.deepseek.com", "https://api.deepseek.com/", "https://api.deepseek.com:443/")) {
            assertEquals("https://api.deepseek.com:443/chat/completions", ChatCompletionEndpoint.resolve(input, null));
        }
        for (String input : List.of("https://api.example.com/v1", "https://api.example.com/v1/", "https://api.example.com/openai/v1/")) {
            assertTrue(ChatCompletionEndpoint.resolve(input, "AUTO").endsWith("/v1/chat/completions"));
        }
    }

    @Test void completeUrlsAndCustomFullPathsArePreserved() {
        for (String path : List.of("/chat/completions", "/v1/chat/completions", "/v1/chat/completions/", "/custom/run")) {
            assertEquals("https://api.example.com:443" + path,
                    ChatCompletionEndpoint.resolve("https://api.example.com" + path, "AUTO"));
        }
        assertEquals("https://api.example.com:443/", ChatCompletionEndpoint.resolve("https://api.example.com/", "FULL_URL"));
        assertEquals("https://api.example.com:443/custom/chat/completions",
                ChatCompletionEndpoint.resolve("https://api.example.com/custom/", "BASE_URL"));
        assertEquals("https://api.example.com:443/v1/chat/completions",
                ChatCompletionEndpoint.resolve("https://api.example.com/v1/chat/completions", "BASE_URL"));
    }

    @Test void parsingCannotEraseForbiddenUrlParts() {
        for (String input : List.of("https://user:pass@api.example.com/", "https://api.example.com/?key=secret",
                "https://api.example.com/#fragment", " https://api.example.com/", "https://api.example.com/\\internal")) {
            assertThrows(IllegalArgumentException.class, () -> ChatCompletionEndpoint.resolve(input, "AUTO"));
        }
        assertThrows(IllegalArgumentException.class, () -> ChatCompletionEndpoint.resolve("https://api.example.com/", "OTHER"));
    }

    @Test void finalPathMustMatchExactPrivateWhitelist() throws Exception {
        var guard = new AiEndpointGuard("http://fixture.test:18081/chat/completions",
                host -> List.of(InetAddress.getByName("127.0.0.1")));
        assertEquals("/chat/completions", guard.validate(ChatCompletionEndpoint.resolve("http://fixture.test:18081", "AUTO")).getPath());
        assertThrows(IllegalArgumentException.class, () -> guard.validate(ChatCompletionEndpoint.resolve("http://fixture.test:18081/other", "BASE_URL")));
        var onlyRoot = new AiEndpointGuard("http://fixture.test:18081/",
                host -> List.of(InetAddress.getByName("127.0.0.1")));
        assertThrows(IllegalArgumentException.class, () -> onlyRoot.validate(ChatCompletionEndpoint.resolve("http://fixture.test:18081", "AUTO")));
        assertEquals("/", onlyRoot.validate(ChatCompletionEndpoint.resolve("http://fixture.test:18081", "FULL_URL")).getPath());
        var publicOnly = new AiEndpointGuard("", host -> List.of(InetAddress.getByName("127.0.0.1")));
        assertThrows(IllegalArgumentException.class, () -> publicOnly.validate(ChatCompletionEndpoint.resolve("https://api.example.com", "AUTO")));
    }
}
