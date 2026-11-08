package com.musealgo.oj.ai.network;

import okhttp3.Dns;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 管理端接口地址的统一 SSRF 防护。
 *
 * <p>公网目标只允许 HTTPS 443，禁止用户信息、query、fragment 与重定向。
 * 保存、测试和正式调用共用本类；连接时 OkHttp 再通过 {@link #guardedDns(URI)} 重新解析，
 * 只要任一解析结果落在私网/保留地址就拒绝，降低 DNS 变化绕过。</p>
 *
 * <p>测试环境的精确目标白名单来自后端属性，管理页没有跳过开关。</p>
 */
@Component
public class AiEndpointGuard {

    private final Set<String> exactPrivateTargets;
    private final Dns resolver;

    @Autowired
    public AiEndpointGuard(@Value("${oj.ai.network.private-targets:}") String configuredTargets) {
        this(configuredTargets, Dns.SYSTEM);
    }

    AiEndpointGuard(String configuredTargets, Dns resolver) {
        this.resolver = resolver == null ? Dns.SYSTEM : resolver;
        Set<String> targets = new LinkedHashSet<>();
        if (configuredTargets != null && !configuredTargets.isBlank()) {
            for (String item : configuredTargets.split(",")) {
                String raw = item.trim();
                if (raw.isEmpty()) {
                    continue;
                }
                try {
                    targets.add(canonicalize(raw, true));
                } catch (IllegalArgumentException e) {
                    throw new IllegalStateException("oj.ai.network.private-targets 含非法目标: " + raw, e);
                }
            }
        }
        this.exactPrivateTargets = Set.copyOf(targets);
    }

    /**
     * 校验管理员填写的完整请求地址，并解析公网目标的全部地址。
     *
     * @return 规范化后的 URI，调用方应使用该 URI，不再重新拼接路径
     */
    public URI validate(String rawUrl) {
        String canonical = canonicalize(rawUrl, true);
        boolean exactPrivateTarget = exactPrivateTargets.contains(canonical);
        URI uri;
        try {
            uri = new URI(canonical);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("接口地址格式非法");
        }
        if (!exactPrivateTarget) {
            if (!"https".equals(uri.getScheme())) {
                throw new IllegalArgumentException("公网接口地址必须使用 HTTPS");
            }
            if (uri.getPort() != 443) {
                throw new IllegalArgumentException("公网接口地址只允许 443 端口");
            }
            List<InetAddress> resolved = resolve(uri.getHost());
            requirePublic(resolved);
        }
        return uri;
    }

    /** 未绑定请求地址的解析器只能访问公网，不能从其他目标继承白名单权限。 */
    public Dns guardedDns() {
        return guardedDns(null);
    }

    /** 私网例外只对当前请求的完整地址生效，不扩大到同主机的其他路径或端口。 */
    public Dns guardedDns(URI endpoint) {
        boolean exactTarget = endpoint != null && isExactPrivateTarget(endpoint.toString());
        String allowedHost = exactTarget ? endpoint.getHost() : null;
        return hostname -> {
            try {
                List<InetAddress> resolved = resolve(hostname);
                if (allowedHost == null || !allowedHost.equalsIgnoreCase(hostname)) {
                    requirePublic(resolved);
                }
                return resolved;
            } catch (IllegalArgumentException e) {
                // 解析失败和地址拒绝都遵循 Dns 的 IOException 契约，避免流式调用抛出未处理异常。
                throw new UnknownHostException(e.getMessage());
            }
        };
    }

    public boolean isExactPrivateTarget(String rawUrl) {
        try {
            return exactPrivateTargets.contains(canonicalize(rawUrl, true));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private List<InetAddress> resolve(String host) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("接口地址缺少主机名");
        }
        try {
            List<InetAddress> addresses = resolver.lookup(host);
            if (addresses == null || addresses.isEmpty()) {
                throw new IllegalArgumentException("接口地址域名无法解析");
            }
            return List.copyOf(addresses);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("接口地址域名无法解析");
        }
    }

    private static void requirePublic(List<InetAddress> addresses) {
        for (InetAddress address : addresses) {
            if (!isPublicAddress(address)) {
                throw new IllegalArgumentException("接口地址不能指向回环、私网或保留地址");
            }
        }
    }

    /** 包内可见供单测：逐项覆盖 IPv4/IPv6 保留段。 */
    static boolean isPublicAddress(InetAddress address) {
        if (address == null) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address && bytes.length == 4) {
            return isPublicIpv4(bytes);
        }
        if (address instanceof Inet6Address && bytes.length == 16) {
            return isPublicIpv6(bytes);
        }
        return false;
    }

    private static boolean isPublicIpv4(byte[] b) {
        int a0 = b[0] & 0xff;
        int a1 = b[1] & 0xff;
        int a2 = b[2] & 0xff;
        return !(a0 == 0
                || a0 == 10
                || a0 == 127
                || (a0 == 100 && a1 >= 64 && a1 <= 127)
                || (a0 == 169 && a1 == 254)
                || (a0 == 172 && a1 >= 16 && a1 <= 31)
                || (a0 == 192 && a1 == 0 && (a2 == 0 || a2 == 2))
                || (a0 == 192 && a1 == 88 && a2 == 99)
                || (a0 == 192 && a1 == 168)
                || (a0 == 198 && (a1 == 18 || a1 == 19))
                || (a0 == 198 && a1 == 51 && a2 == 100)
                || (a0 == 203 && a1 == 0 && a2 == 113)
                || a0 >= 224);
    }

    private static boolean isPublicIpv6(byte[] b) {
        if (b.length != 16) {
            return false;
        }
        int first16 = ((b[0] & 0xff) << 8) | (b[1] & 0xff);
        // 只接受 2000::/3 全球单播，其余（回环、ULA、链路本地、IPv4 嵌入等）全部拒绝。
        if (first16 < 0x2000 || first16 > 0x3fff) {
            return false;
        }
        if (first16 == 0x2001) {
            // 2001:db8::/32 文档、2001::/32 Teredo、2001:2::/48 基准测试、ORCHID。
            if ((b[2] & 0xff) == 0x0d && (b[3] & 0xff) == 0xb8) {
                return false;
            }
            if ((b[2] & 0xff) == 0x00 && (b[3] & 0xff) == 0x00) {
                return false;
            }
            if ((b[2] & 0xff) == 0x00 && (b[3] & 0xff) == 0x02) {
                return false;
            }
            int second16 = ((b[2] & 0xff) << 8) | (b[3] & 0xff);
            if ((second16 & 0xfff0) == 0x0010 || (second16 & 0xfff0) == 0x0020) {
                return false;
            }
        }
        // 6to4 可嵌入私网 IPv4，首版直接拒绝。
        if (first16 == 0x2002) {
            return false;
        }
        // 3fff::/20 文档段。
        return !(first16 == 0x3fff && ((b[2] & 0xff) & 0xf0) == 0);
    }

    private static String canonicalize(String rawUrl, boolean allowHttp) {
        if (rawUrl == null) {
            throw new IllegalArgumentException("接口地址不能为空");
        }
        String raw = rawUrl.trim();
        if (raw.isEmpty()) {
            throw new IllegalArgumentException("接口地址不能为空");
        }
        if (!raw.equals(rawUrl) || raw.length() > 512) {
            throw new IllegalArgumentException("接口地址长度或空白字符非法");
        }
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch <= 0x20 || ch == 0x7f || ch == '\\') {
                throw new IllegalArgumentException("接口地址含非法字符");
            }
        }
        URI uri;
        try {
            uri = new URI(raw).normalize();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("接口地址格式非法");
        }
        if (!uri.isAbsolute() || uri.isOpaque() || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("接口地址必须是完整 URL，且不能包含用户信息、query 或 fragment");
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"https".equals(scheme) && !(allowHttp && "http".equals(scheme))) {
            throw new IllegalArgumentException(allowHttp
                    ? "测试目标只允许 HTTP 或 HTTPS"
                    : "接口地址必须使用 HTTPS");
        }
        int port = uri.getPort();
        if (port == -1) {
            port = "https".equals(scheme) ? 443 : 80;
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("接口地址端口非法");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        return scheme + "://" + host + ":" + port + path;
    }
}
