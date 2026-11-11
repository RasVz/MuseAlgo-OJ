package com.musealgo.oj.ai.network;

import okhttp3.Dns;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.xbill.DNS.*;
import org.xbill.DNS.Record;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

/** RFC 8484 transport. No DNS cache, redirects, proxy, or system-DNS bootstrap. */
final class PublicDohDns implements Dns, AutoCloseable {
    private static final int MAX_BYTES = 65535;
    private final HttpUrl endpoint;
    private final OkHttpClient client;

    PublicDohDns(String url, String bootstrapIps) {
        endpoint = HttpUrl.get(url);
        if (!endpoint.isHttps() || endpoint.port() != 443 || !endpoint.username().isEmpty()
                || !endpoint.password().isEmpty() || endpoint.query() != null || endpoint.fragment() != null) {
            throw new IllegalArgumentException("DoH 必须是无凭证、query、fragment 的 HTTPS 443 地址");
        }
        List<InetAddress> bootstrap = new ArrayList<>();
        for (String ip : bootstrapIps.split(",")) {
            byte[] bytes = Address.toByteArray(ip.trim(), Address.IPv4);
            if (bytes == null) bytes = Address.toByteArray(ip.trim(), Address.IPv6);
            if (bytes == null) throw new IllegalArgumentException("DoH bootstrap 必须是公网 IP");
            try {
                InetAddress address = InetAddress.getByAddress(bytes);
                if (!AiEndpointGuard.isPublicAddress(address)) throw new IllegalArgumentException("DoH bootstrap 必须是公网 IP");
                bootstrap.add(address);
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("DoH bootstrap IP 无效");
            }
        }
        if (bootstrap.isEmpty()) throw new IllegalArgumentException("DoH bootstrap 不能为空");
        client = new OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
                .dns(host -> {
                    if (!endpoint.host().equalsIgnoreCase(host)) throw new UnknownHostException("Unexpected DoH host");
                    return List.copyOf(bootstrap);
                })
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
                .callTimeout(5, TimeUnit.SECONDS).build();
    }

    // Transport seam for deterministic tests, never used by application configuration.
    PublicDohDns(HttpUrl endpoint, OkHttpClient client) {
        this.endpoint = endpoint;
        this.client = client;
    }

    @Override
    public void close() {
        client.connectionPool().evictAll();
        client.dispatcher().executorService().shutdownNow();
    }

    @Override
    public List<InetAddress> lookup(String hostname) throws UnknownHostException {
        List<Call> calls = new ArrayList<>();
        try {
            Name name = Name.fromString(hostname.endsWith(".") ? hostname : hostname + ".");
            List<InetAddress> result = new ArrayList<>();
            // No partial success: NODATA is valid, timeout/SERVFAIL/malformed A or AAAA is not.
            var ipv4 = query(name, Type.A, calls);
            var ipv6 = query(name, Type.AAAA, calls);
            CompletableFuture.allOf(ipv4, ipv6).get(6, TimeUnit.SECONDS);
            result.addAll(ipv4.join());
            result.addAll(ipv6.join());
            if (result.isEmpty()) throw new IOException("No addresses");
            AiEndpointGuard.requirePublic(result);
            return List.copyOf(result);
        } catch (IOException | ExecutionException | TimeoutException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            if (e.getCause() instanceof AiEndpointException rejected) throw rejected;
            var failure = new UnknownHostException("检测到代理地址，但公网解析未完成；请检查后端到加密 DNS 的连接后重试");
            failure.initCause(e);
            throw failure;
        } finally {
            calls.forEach(Call::cancel);
        }
    }

    private CompletableFuture<List<InetAddress>> query(Name name, int type, List<Call> calls) {
        Message query = Message.newQuery(Record.newRecord(name, type, DClass.IN));
        query.getHeader().setID(0);
        Request request = new Request.Builder().url(endpoint)
                .header("Accept", "application/dns-message")
                .post(RequestBody.create(query.toWire(), MediaType.get("application/dns-message"))).build();
        var future = new CompletableFuture<List<InetAddress>>();
        Call call = client.newCall(request);
        calls.add(call);
        call.enqueue(new Callback() {
            @Override public void onFailure(Call ignored, IOException e) { future.completeExceptionally(e); }
            @Override public void onResponse(Call ignored, Response response) {
                try (response) {
                    if (response.code() != 200 || response.body() == null) throw new IOException("DoH response failed");
                    MediaType contentType = response.body().contentType();
                    if (contentType == null || !"application".equals(contentType.type())
                            || !"dns-message".equals(contentType.subtype())) throw new IOException("Invalid DoH content type");
                    byte[] wire = response.body().byteStream().readNBytes(MAX_BYTES + 1);
                    if (wire.length > MAX_BYTES) throw new IOException("DoH response too large");
                    future.complete(addresses(query, wire));
                } catch (IOException | RuntimeException e) {
                    future.completeExceptionally(e);
                }
            }
        });
        return future;
    }

    static List<InetAddress> addresses(Message query, byte[] wire) throws IOException {
        Message answer = new Message(wire);
        Header h = answer.getHeader();
        if (!h.getFlag(Flags.QR) || h.getFlag(Flags.TC) || h.getOpcode() != Opcode.QUERY
                || h.getID() != query.getHeader().getID() || answer.getRcode() != Rcode.NOERROR
                || h.getCount(Section.QUESTION) != 1 || !query.getQuestion().equals(answer.getQuestion())) {
            throw new IOException("Invalid DoH answer");
        }
        Name target = query.getQuestion().getName();
        Set<Name> chain = new HashSet<>();
        List<Record> records = answer.getSection(Section.ANSWER);
        for (int depth = 0; depth <= 16; depth++) {
            if (!chain.add(target)) throw new IOException("CNAME cycle");
            Name next = null;
            List<InetAddress> addresses = new ArrayList<>();
            for (Record record : records) {
                // Even unexpected address records may not conceal a private result.
                InetAddress address = record instanceof ARecord a ? a.getAddress()
                        : record instanceof AAAARecord aaaa ? aaaa.getAddress() : null;
                if (address != null) AiEndpointGuard.requirePublic(List.of(address));
                if (!record.getName().equals(target)) continue;
                if (record.getDClass() != DClass.IN) throw new IOException("Unexpected DNS class");
                if (record instanceof CNAMERecord cname) {
                    if (next != null && !next.equals(cname.getTarget())) throw new IOException("Ambiguous CNAME");
                    next = cname.getTarget();
                } else if (address != null) {
                    if (record.getType() != query.getQuestion().getType()) throw new IOException("Unexpected DNS type");
                    addresses.add(address);
                }
            }
            if (next == null) return addresses;
            if (!addresses.isEmpty()) throw new IOException("CNAME and address coexist");
            target = next;
        }
        throw new IOException("CNAME chain too long");
    }
}
