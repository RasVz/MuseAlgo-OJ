package com.musealgo.oj.ai.network;

import okhttp3.Dns;
import org.junit.jupiter.api.Test;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class FakeIpRecoveringDnsTest {
    private static Dns fixed(String... ips) {
        return host -> {
            var result = new java.util.ArrayList<InetAddress>();
            for (String ip : ips) result.add(InetAddress.getByName(ip));
            return result;
        };
    }

    @Test void fakeIpRecoveredAtSaveAndConnectionTime() throws Exception {
        AtomicInteger lookups = new AtomicInteger();
        try (var dns = new FakeIpRecoveringDns(fixed("198.18.0.20", "198.19.1.2"), host -> {
            lookups.incrementAndGet(); return fixed("8.8.8.8").lookup(host);
        }, true, 1000)) {
            var guard = new AiEndpointGuard("", dns);
            var endpoint = guard.validate("https://api.example.com/v1/chat/completions");
            assertEquals("8.8.8.8", guard.guardedDns(endpoint).lookup(endpoint.getHost()).get(0).getHostAddress());
            assertEquals(2, lookups.get());
        }
    }

    @Test void normalPrivateMixedAndLiteralResultsNeverInvokeDoh() throws Exception {
        for (String[] ips : List.of(new String[]{"8.8.8.8"}, new String[]{"10.0.0.1"},
                new String[]{"8.8.8.8", "198.18.0.20"}, new String[]{"198.18.0.20", "127.0.0.1"})) {
            try (var dns = new FakeIpRecoveringDns(fixed(ips), host -> { fail("DoH must not run"); return null; }, true, 1000)) {
                var guard = new AiEndpointGuard("", dns);
                if (ips.length == 1 && ips[0].equals("8.8.8.8")) guard.validate("https://api.example.com/");
                else assertThrows(AiEndpointException.class, () -> guard.validate("https://api.example.com/"));
            }
        }
        try (var dns = new FakeIpRecoveringDns(fixed("198.18.0.20"), host -> { fail(); return null; }, true, 1000)) {
            assertThrows(AiEndpointException.class, () -> new AiEndpointGuard("", dns).validate("https://198.18.0.20/"));
        }
    }

    @Test void recoveredPrivateOrEmptyAnswersFailClosed() {
        for (String[] ips : List.of(new String[]{"10.0.0.1"}, new String[]{"8.8.8.8", "::1"}, new String[]{})) {
            try (var dns = new FakeIpRecoveringDns(fixed("198.18.0.1"), fixed(ips), true, 1000)) {
                assertThrows(AiEndpointException.class, () -> new AiEndpointGuard("", dns).validate("https://api.example.com/"));
            }
        }
    }

    @Test void nxdomainAndTimeoutAreEnvironmentErrorsWithoutDohFallback() {
        try (var dns = new FakeIpRecoveringDns(host -> { throw new UnknownHostException(); },
                host -> { fail(); return null; }, true, 1000)) {
            var failure = assertThrows(AiEndpointException.class,
                    () -> new AiEndpointGuard("", dns).validate("https://api.example.com/"));
            assertEquals("AI_DNS_UNAVAILABLE", failure.errorCode());
        }
        try (var dns = new FakeIpRecoveringDns(host -> {
            try { Thread.sleep(5000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            return List.of();
        }, fixed("8.8.8.8"), true, 20)) {
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(1),
                    () -> assertThrows(UnknownHostException.class, () -> dns.lookup("api.example.com")));
        }
    }

    @Test void rebindingAndExactWhitelistKeepTheirMeaning() throws Exception {
        AtomicInteger call = new AtomicInteger();
        try (var dns = new FakeIpRecoveringDns(host -> fixed(call.incrementAndGet() == 1 ? "8.8.8.8" : "10.0.0.1").lookup(host),
                host -> { fail(); return null; }, true, 1000)) {
            var guard = new AiEndpointGuard("", dns);
            guard.validate("https://api.example.com/");
            assertThrows(UnknownHostException.class, () -> guard.guardedDns().lookup("api.example.com"));
        }
        try (var dns = new FakeIpRecoveringDns(fixed("198.18.0.20"), fixed("8.8.8.8"), true, 1000)) {
            var guard = new AiEndpointGuard("https://api.example.com/exact", dns);
            var exact = guard.validate("https://api.example.com/exact");
            assertEquals("198.18.0.20", guard.guardedDns(exact).lookup("api.example.com").get(0).getHostAddress());
            var other = guard.validate("https://api.example.com/other");
            assertEquals("8.8.8.8", guard.guardedDns(other).lookup("api.example.com").get(0).getHostAddress());
        }
    }

    @Test void stuckNativeLookupsAreBoundedAndRecoverAfterRelease() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(4);
        var release = new java.util.concurrent.CountDownLatch(1);
        var callers = java.util.concurrent.Executors.newFixedThreadPool(4);
        try (var dns = new FakeIpRecoveringDns(host -> {
            entered.countDown();
            while (release.getCount() != 0) {
                try { release.await(); } catch (InterruptedException ignored) { /* emulate native resolver */ }
            }
            return fixed("8.8.8.8").lookup(host);
        }, fixed("8.8.8.8"), true, 100)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 4; i++) futures.add(callers.submit(() -> assertThrows(UnknownHostException.class, () -> dns.lookup("api.example.com"))));
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
            for (var future : futures) future.get(2, java.util.concurrent.TimeUnit.SECONDS);
            assertThrows(UnknownHostException.class, () -> dns.lookup("api.example.com"));
            release.countDown();
            assertEquals("8.8.8.8", dns.lookup("api.example.com").get(0).getHostAddress());
        } finally { release.countDown(); callers.shutdownNow(); }
    }

    @Test void recoveryCanBeDisabledWithoutAllowingFakeIp() {
        try (var dns = new FakeIpRecoveringDns(fixed("198.18.0.1"), host -> { fail(); return null; }, false, 1000)) {
            assertThrows(AiEndpointException.class, () -> new AiEndpointGuard("", dns).validate("https://api.example.com/"));
        }
    }
}
