package com.musealgo.oj.ai.network;

import java.net.URI;

/** Resolves administrator input before the final URL enters the existing SSRF guard. */
public final class ChatCompletionEndpoint {
    private static final String COMPLETIONS = "/chat/completions";

    private ChatCompletionEndpoint() { }

    public static String resolve(String rawUrl, String addressType) {
        String type = addressType == null ? "AUTO" : addressType;
        if (!type.equals("AUTO") && !type.equals("BASE_URL") && !type.equals("FULL_URL")) {
            throw new IllegalArgumentException("接口地址填写方式无效");
        }
        String canonical = AiEndpointGuard.canonicalize(rawUrl, true);
        URI uri = URI.create(canonical);
        String path = uri.getRawPath();
        String trimmed = path.replaceFirst("/+$", "");
        boolean complete = trimmed.endsWith(COMPLETIONS);
        boolean base = trimmed.isEmpty() || trimmed.matches(".*/v[0-9]+");
        if (!type.equals("FULL_URL") && !complete && (type.equals("BASE_URL") || base)) {
            return uri.getScheme() + "://" + uri.getRawAuthority() + trimmed + COMPLETIONS;
        }
        return canonical;
    }
}
