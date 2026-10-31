package com.musealgo.oj.ai.network;

import okhttp3.Dns;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiEndpointGuardTest {

    @Test
    void acceptsPublicHttpsAndCanonicalizesDefaultPort() throws Exception {
        AiEndpointGuard guard = new AiEndpointGuard("", fixedDns(Map.ofAddresses("api.example.com", "1.1.1.1")));

        assertEquals("https://api.example.com:443/v1/chat/completions",
                guard.validate("https://api.example.com/v1/chat/completions").toString());
    }

    @Test
    void rejectsHttpCredentialsQueryFragmentAndNon443Port() throws Exception {
        Dns dns = fixedDns(Map.ofAddresses("api.example.com", "1.1.1.1"));
        AiEndpointGuard guard = new AiEndpointGuard("", dns);

        assertThrows(IllegalArgumentException.class,
                () -> guard.validate("http://api.example.com/v1/chat/completions"));
        assertThrows(IllegalArgumentException.class,
                () -> guard.validate("https://user:pass@api.example.com/v1/chat/completions"));
        assertThrows(IllegalArgumentException.class,
                () -> guard.validate("https://api.example.com/v1/chat/completions?key=secret"));
        assertThrows(IllegalArgumentException.class,
                () -> guard.validate("https://api.example.com/v1/chat/completions#fragment"));
        assertThrows(IllegalArgumentException.class,
                () -> guard.validate("https://api.example.com:8443/v1/chat/completions"));
    }

    @Test
    void rejectsLoopbackPrivateLinkLocalReservedAndMixedAnswers() throws Exception {
        assertRejected("127.0.0.1", "10.0.0.1", "169.254.1.1", "192.168.1.1",
                "100.64.0.1", "198.18.0.1", "224.0.0.1", "0.0.0.0");
        AiEndpointGuard mixed = new AiEndpointGuard("",
                fixedDns(Map.ofAddresses("mixed.example.com", "1.1.1.1", "10.0.0.1")));
        assertThrows(IllegalArgumentException.class,
                () -> mixed.validate("https://mixed.example.com/v1/chat/completions"));
    }

    @Test
    void rejectsIpv6PrivateAndSpecialRanges() throws Exception {
        assertFalse(AiEndpointGuard.isPublicAddress(InetAddress.getByName("::1")));
        assertFalse(AiEndpointGuard.isPublicAddress(InetAddress.getByName("fc00::1")));
        assertFalse(AiEndpointGuard.isPublicAddress(InetAddress.getByName("fe80::1")));
        assertFalse(AiEndpointGuard.isPublicAddress(InetAddress.getByName("2001:db8::1")));
        assertFalse(AiEndpointGuard.isPublicAddress(InetAddress.getByName("2002:0a00:0001::1")));
        assertFalse(AiEndpointGuard.isPublicAddress(InetAddress.getByName("::ffff:127.0.0.1")));
        assertTrue(AiEndpointGuard.isPublicAddress(InetAddress.getByName("2606:4700:4700::1111")));
    }

    @Test
    void dnsRebindingToPrivateAddressIsBlockedAtConnectionTime() throws Exception {
        AtomicBoolean rebound = new AtomicBoolean(false);
        Dns dns = host -> {
            if (rebound.get()) {
                return List.of(InetAddress.getByName("10.0.0.7"));
            }
            return List.of(InetAddress.getByName("1.1.1.1"));
        };
        AiEndpointGuard guard = new AiEndpointGuard("", dns);
        guard.validate("https://api.example.com/v1/chat/completions");

        rebound.set(true);
        assertThrows(IOException.class, () -> guard.guardedDns().lookup("api.example.com"));
    }

    @Test
    void nonAllowlistedPathCannotInheritPrivateDnsAllowanceFromSameHost() throws Exception {
        AtomicBoolean rebound = new AtomicBoolean(false);
        Dns dns = host -> List.of(InetAddress.getByName(rebound.get() ? "10.0.0.7" : "1.1.1.1"));
        AiEndpointGuard guard = new AiEndpointGuard("https://api.example.com/allowed", dns);
        URI endpoint = guard.validate("https://api.example.com/other");

        rebound.set(true);

        assertThrows(IOException.class, () -> guard.guardedDns().lookup("api.example.com"),
                "白名单中的一条路径不能放行同主机的其他路径");
        assertThrows(IOException.class, () -> guard.guardedDns(endpoint).lookup("api.example.com"));
    }

    @Test
    void scopedDnsAllowsOnlyTheExactPrivateEndpoint() throws Exception {
        AiEndpointGuard guard = new AiEndpointGuard("http://fixture.test:18081/v1/chat/completions",
                host -> List.of(InetAddress.getByName("127.0.0.1")));
        URI endpoint = guard.validate("http://fixture.test:18081/v1/chat/completions");

        assertEquals(InetAddress.getByName("127.0.0.1"),
                guard.guardedDns(endpoint).lookup("fixture.test").get(0));
        assertThrows(IOException.class, () -> guard.guardedDns(endpoint).lookup("other.test"));
    }

    @Test
    void connectionDnsResolutionFailureUsesTheIoFailureContract() {
        AiEndpointGuard guard = new AiEndpointGuard("", host -> {
            throw new UnknownHostException("fixture DNS failure");
        });

        assertThrows(UnknownHostException.class, () -> guard.guardedDns().lookup("missing.test"));
    }

    @Test
    void exactPrivateTargetAllowlistIsHttpOnlyForThatFullUrl() throws Exception {
        Dns dns = fixedDns(Map.ofAddresses("127.0.0.1", "127.0.0.1"));
        AiEndpointGuard guard = new AiEndpointGuard(
                "http://127.0.0.1:18081/v1/chat/completions", dns);

        String allowed = "http://127.0.0.1:18081/v1/chat/completions";
        assertEquals(allowed, guard.validate(allowed).toString());
        assertTrue(guard.isExactPrivateTarget(allowed));
        assertThrows(IllegalArgumentException.class,
                () -> guard.validate("http://127.0.0.1:18081/other"));
    }

    private static void assertRejected(String... addresses) throws UnknownHostException {
        for (String address : addresses) {
            assertFalse(AiEndpointGuard.isPublicAddress(InetAddress.getByName(address)), address);
        }
    }

    private static Dns fixedDns(List<InetAddress> addresses) {
        return host -> addresses;
    }

    private static final class Map {
        private Map() {
        }

        static List<InetAddress> ofAddresses(String ignoredHost, String... addresses) throws UnknownHostException {
            InetAddress[] values = new InetAddress[addresses.length];
            for (int i = 0; i < addresses.length; i++) {
                values[i] = InetAddress.getByName(addresses[i]);
            }
            return List.of(values);
        }
    }
}
