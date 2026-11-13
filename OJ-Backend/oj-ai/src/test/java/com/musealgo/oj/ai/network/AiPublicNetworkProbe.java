package com.musealgo.oj.ai.network;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import java.net.InetAddress;
import java.net.Proxy;
import java.time.Duration;
import java.util.List;

/** Explicit opt-in read-only probe: GET homepage, no Authorization, no model request. */
public final class AiPublicNetworkProbe {
    public static void main(String[] args) throws Exception {
        try (var resolver = new FakeIpRecoveringDns(
                args.length > 0 && "system".equals(args[0]) ? okhttp3.Dns.SYSTEM : host -> List.of(InetAddress.getByName("198.18.0.20")),
                new PublicDohDns("https://dns.alidns.com/dns-query", "223.5.5.5,223.6.6.6"), true, 8000)) {
            var guard = new AiEndpointGuard("", resolver);
            var endpoint = guard.validate("https://api.deepseek.com/");
            var client = new OkHttpClient.Builder().dns(guard.guardedDns(endpoint)).proxy(Proxy.NO_PROXY)
                    .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                    .callTimeout(Duration.ofSeconds(10)).build();
            try (var response = client.newCall(new Request.Builder().url(endpoint.toString()).get().build()).execute()) {
                if (response.handshake() == null) throw new AssertionError("TLS was not verified");
                System.out.println("fake-ip recovery -> api.deepseek.com verified TLS, HTTP " + response.code()
                        + "; no API key or model invocation");
            } finally {
                client.connectionPool().evictAll(); client.dispatcher().executorService().shutdownNow();
            }
        }
    }
}
