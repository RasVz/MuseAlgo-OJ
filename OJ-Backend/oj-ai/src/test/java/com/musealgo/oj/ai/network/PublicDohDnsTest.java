package com.musealgo.oj.ai.network;

import com.sun.net.httpserver.HttpServer;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.xbill.DNS.*;
import org.xbill.DNS.Record;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PublicDohDnsTest {
    static Message query(int type) throws Exception {
        var query = Message.newQuery(Record.newRecord(Name.fromString("api.example.com."), type, DClass.IN));
        query.getHeader().setID(0); return query;
    }
    static Message answer(Message query) {
        var answer = new Message(query.getHeader().getID());
        answer.getHeader().setFlag(Flags.QR);
        answer.addRecord(query.getQuestion(), Section.QUESTION); return answer;
    }

    @Test void validatesQuestionFlagsCnameAndAllAddresses() throws Exception {
        var q = query(Type.A); var a = answer(q);
        var alias = Name.fromString("cdn.example.com.");
        a.addRecord(new CNAMERecord(q.getQuestion().getName(), DClass.IN, 60, alias), Section.ANSWER);
        a.addRecord(new ARecord(alias, DClass.IN, 60, InetAddress.getByName("8.8.8.8")), Section.ANSWER);
        assertEquals("8.8.8.8", PublicDohDns.addresses(q, a.toWire()).get(0).getHostAddress());
        a.addRecord(new ARecord(Name.fromString("unrelated.example.com."), DClass.IN, 60,
                InetAddress.getByName("127.0.0.1")), Section.ANSWER);
        assertThrows(AiEndpointException.class, () -> PublicDohDns.addresses(q, a.toWire()));
        for (int flag : List.of(Flags.TC)) {
            var invalid = answer(q); invalid.getHeader().setFlag(flag);
            assertThrows(IOException.class, () -> PublicDohDns.addresses(q, invalid.toWire()));
        }
        assertThrows(IOException.class, () -> PublicDohDns.addresses(q, answer(query(Type.AAAA)).toWire()));
        assertThrows(IOException.class, () -> PublicDohDns.addresses(q, new byte[]{0, 1}));
        var cycle = answer(q);
        cycle.addRecord(new CNAMERecord(q.getQuestion().getName(), DClass.IN, 60, q.getQuestion().getName()), Section.ANSWER);
        assertThrows(IOException.class, () -> PublicDohDns.addresses(q, cycle.toWire()));
    }

    @Test void httpTransportRequiresBothQueriesAndBoundsChunkedResponses() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var mode = new AtomicInteger();
        server.createContext("/dns-query", exchange -> {
            var q = new Message(exchange.getRequestBody().readAllBytes());
            var a = answer(q);
            if (q.getQuestion().getType() == Type.A) a.addRecord(new ARecord(q.getQuestion().getName(),
                    DClass.IN, 60, InetAddress.getByName("8.8.8.8")), Section.ANSWER);
            else if (mode.get() == 1) a.getHeader().setRcode(Rcode.SERVFAIL);
            if (mode.get() == 3) { exchange.getResponseHeaders().set("Location", "/elsewhere"); exchange.sendResponseHeaders(302, -1); exchange.close(); return; }
            exchange.getResponseHeaders().set("Content-Type", "application/dns-message");
            exchange.sendResponseHeaders(200, 0);
            try { exchange.getResponseBody().write(mode.get() == 2 ? new byte[70000] : a.toWire()); }
            finally { exchange.close(); }
        });
        server.start();
        var client = new OkHttpClient.Builder().followRedirects(false).callTimeout(java.time.Duration.ofSeconds(2)).build();
        var dns = new PublicDohDns(HttpUrl.get("http://127.0.0.1:" + server.getAddress().getPort() + "/dns-query"), client);
        try {
            assertEquals("8.8.8.8", dns.lookup("api.example.com").get(0).getHostAddress());
            for (int failure : List.of(1, 2, 3)) {
                mode.set(failure);
                assertThrows(UnknownHostException.class, () -> dns.lookup("api.example.com"));
            }
        } finally { server.stop(0); client.connectionPool().evictAll(); client.dispatcher().executorService().shutdownNow(); }
    }

    @Test void productionBootstrapRejectsNamesPrivateIpsAndUnsafeUrls() {
        for (String ip : List.of("localhost", "127.0.0.1", "198.18.0.1", "::1", "")) {
            assertThrows(IllegalArgumentException.class, () -> new PublicDohDns("https://dns.example.com/dns-query", ip));
        }
        for (String url : List.of("http://dns.example.com/", "https://dns.example.com:444/", "https://user:pass@dns.example.com/", "https://dns.example.com/?x=1")) {
            assertThrows(IllegalArgumentException.class, () -> new PublicDohDns(url, "8.8.8.8"));
        }
    }
}
