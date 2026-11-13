package com.musealgo.oj.ai.gateway;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.musealgo.oj.ai.network.AiEndpointGuard;
import com.musealgo.oj.ai.runtime.AiServiceSnapshot;
import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.HttpsConfigurator;
import okhttp3.Dns;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.KeyManager;
import java.net.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiConnectionPolicyTest {
    @Test void privateSocketsCannotBeReusedByPublicPathAndTlsStillChecksOriginalHostname() throws Exception {
        var certificate = new HeldCertificate.Builder().commonName("api.example.com")
                .addSubjectAlternativeName("api.example.com").build();
        var serverTls = new HandshakeCertificates.Builder().heldCertificate(certificate).build();
        var ssl = SSLContext.getInstance("TLS");
        ssl.init(new KeyManager[]{serverTls.keyManager()}, null, null);
        var server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(ssl));
        var hostHeaders = new ArrayList<String>();
        server.createContext("/", exchange -> {
            hostHeaders.add(exchange.getRequestHeaders().getFirst("Host"));
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = "{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        var currentIp = new AtomicReference<>("127.0.0.1");
        Dns dns = host -> List.of(InetAddress.getByName(currentIp.get()));
        var guard = mock(AiEndpointGuard.class);
        when(guard.validate(anyString())).thenAnswer(call -> URI.create(call.getArgument(0)));
        when(guard.isExactPrivateTarget(anyString())).thenAnswer(call -> URI.create(call.getArgument(0)).getPath().equals("/allowed"));
        when(guard.guardedDns()).thenReturn(dns);
        when(guard.guardedDns(any(URI.class))).thenReturn(dns);
        var client = new ChatCompletionsClient(guard);
        var base = (OkHttpClient) ReflectionTestUtils.getField(client, "client");
        assertNotNull(base); assertEquals(Proxy.NO_PROXY, base.proxy());
        assertEquals(List.of(Protocol.HTTP_1_1), base.protocols());
        var clientTls = new HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate()).build();
        var connectedIps = new ArrayList<String>();
        // Only the test socket maps validated addresses to the local TLS fixture.
        SocketFactory sockets = new SocketFactory() {
            @Override public Socket createSocket() {
                return new Socket() {
                    @Override public void connect(SocketAddress address, int timeout) throws IOException {
                        connectedIps.add(((InetSocketAddress) address).getAddress().getHostAddress());
                        super.connect(new InetSocketAddress("127.0.0.1", server.getAddress().getPort()), timeout);
                    }
                };
            }
            @Override public Socket createSocket(String h, int p) { throw new UnsupportedOperationException(); }
            @Override public Socket createSocket(String h, int p, InetAddress l, int lp) { throw new UnsupportedOperationException(); }
            @Override public Socket createSocket(InetAddress h, int p) { throw new UnsupportedOperationException(); }
            @Override public Socket createSocket(InetAddress h, int p, InetAddress l, int lp) { throw new UnsupportedOperationException(); }
        };
        var testClient = base.newBuilder().socketFactory(sockets)
                .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager()).build();
        ReflectionTestUtils.setField(client, "client", testClient);
        JSONArray messages = new JSONArray().put(new JSONObject().set("role", "user").set("content", "test"));
        try {
            assertTrue(client.complete(AiServiceSnapshot.legacy(URI.create("https://api.example.com/allowed"), "test", "synthetic", false, 10), messages, false).success());
            currentIp.set("8.8.8.8");
            assertTrue(client.complete(AiServiceSnapshot.legacy(URI.create("https://api.example.com/other"), "test", "synthetic", false, 10), messages, false).success());
            assertEquals(List.of("127.0.0.1", "8.8.8.8"), connectedIps);
            assertEquals(List.of("api.example.com", "api.example.com"), hostHeaders);
            var wrongHost = client.complete(AiServiceSnapshot.legacy(URI.create("https://other.example.com/other"), "test", "synthetic", false, 10), messages, false);
            assertFalse(wrongHost.success(), "Original hostname must still match the certificate");
            assertEquals(2, hostHeaders.size(), "TLS rejection must happen before any HTTP credential is sent");
        } finally {
            server.stop(0); testClient.connectionPool().evictAll(); testClient.dispatcher().executorService().shutdownNow();
        }
    }
}
