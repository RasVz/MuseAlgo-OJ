package com.musealgo.oj.ai.network;

import jakarta.annotation.PreDestroy;
import okhttp3.Dns;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.*;

/** Recovers only all-fake-ip answers. Public/private/mixed answers retain their existing meaning. */
@Component
public class FakeIpRecoveringDns implements Dns, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(FakeIpRecoveringDns.class);
    private final Dns system;
    private final Dns doh;
    private final boolean enabled;
    private final int timeoutMillis;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), task -> {
                Thread thread = new Thread(task, "ai-endpoint-dns");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    @Autowired
    public FakeIpRecoveringDns(@Value("${oj.ai.network.fake-ip-recovery:true}") boolean enabled,
                              @Value("${oj.ai.network.doh-url:https://dns.alidns.com/dns-query}") String url,
                              @Value("${oj.ai.network.doh-bootstrap:223.5.5.5,223.6.6.6}") String bootstrap) {
        this(Dns.SYSTEM, new PublicDohDns(url, bootstrap), enabled, 8000);
    }

    FakeIpRecoveringDns(Dns system, Dns doh, boolean enabled, int timeoutMillis) {
        this.system = system;
        this.doh = doh;
        this.enabled = enabled;
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public List<InetAddress> lookup(String hostname) throws UnknownHostException {
        return bounded(() -> {
            List<InetAddress> addresses = system.lookup(hostname);
            if (enabled && !literal(hostname) && addresses != null && !addresses.isEmpty()
                    && addresses.stream().allMatch(FakeIpRecoveringDns::fakeIp)) {
                List<InetAddress> recovered = doh.lookup(hostname);
                if (recovered == null || recovered.isEmpty()) throw new UnknownHostException("公网解析没有返回地址");
                AiEndpointGuard.requirePublic(recovered);
                log.info("AI endpoint DNS recovered from fake-ip: host={}", hostname);
                return List.copyOf(recovered);
            }
            return addresses;
        });
    }

    List<InetAddress> systemLookup(String hostname) throws UnknownHostException {
        return bounded(() -> system.lookup(hostname));
    }

    public boolean recoveryEnabled() { return enabled; }

    private List<InetAddress> bounded(Callable<List<InetAddress>> lookup) throws UnknownHostException {
        Future<List<InetAddress>> future;
        try {
            future = workers.submit(lookup);
        } catch (RejectedExecutionException e) {
            throw new UnknownHostException("地址解析繁忙，请稍后重试");
        }
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UnknownHostException("地址解析已取消");
        } catch (TimeoutException e) {
            throw new UnknownHostException("地址解析超时，请检查后端网络后重试");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof AiEndpointException rejected) throw rejected;
            if (e.getCause() instanceof UnknownHostException unavailable) throw unavailable;
            throw new UnknownHostException("地址解析未完成，请稍后重试");
        } finally {
            future.cancel(true);
            workers.purge();
        }
    }

    private static boolean literal(String hostname) {
        return hostname.indexOf(':') >= 0 || hostname.matches("[0-9.]+");
    }

    static boolean fakeIp(InetAddress address) {
        if (!(address instanceof Inet4Address)) return false;
        byte[] b = address.getAddress();
        return (b[0] & 255) == 198 && ((b[1] & 255) == 18 || (b[1] & 255) == 19);
    }

    @Override
    @PreDestroy
    public void close() {
        workers.shutdownNow();
        if (doh instanceof PublicDohDns publicDns) publicDns.close();
    }
}
