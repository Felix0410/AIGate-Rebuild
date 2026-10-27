package com.felix.aigatecore.catalog;

import com.felix.aigatecore.web.ApiException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import jakarta.annotation.PreDestroy;
import java.util.function.Function;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Catalog登记和就绪检查的端点安全校验，只解析DNS，绝不打开Provider HTTP连接。
 *
 * @author AIGate
 */
@Component
public class CatalogEndpointValidator {

    private static final List<String> PRIVATE_RANGES = List.of("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16",
        "fc00::/7");
    private static final List<String> FORBIDDEN_RANGES = List.of("0.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8",
        "169.254.0.0/16", "192.0.0.0/24", "192.0.2.0/24", "198.18.0.0/15", "198.51.100.0/24",
        "203.0.113.0/24", "168.63.129.16/32", "fd00:ec2::254/128", "2001::/23", "3fff::/20", "224.0.0.0/4",
        "240.0.0.0/4", "2001:db8::/32", "2001::/32", "2002::/16");

    private final Function<String, InetAddress[]> resolver;

    // 解析等待和排队都有上限，DNS阻塞不能耗尽公共线程池或无限创建后台任务。
    private final ThreadPoolExecutor lookups = new ThreadPoolExecutor(2, 8, 30, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(32), Thread.ofVirtual().name("catalog-dns-", 0).factory(),
        new ThreadPoolExecutor.AbortPolicy());

    /**
     * 使用JDK解析器；实际Provider出站仍留待P7单独实现和验证。
     */
    public CatalogEndpointValidator() {
        this(CatalogEndpointValidator::resolveAddresses);
    }

    /**
     * 注入受控解析器进行安全边界测试。
     *
     * @param resolver DNS解析实现
     */
    public CatalogEndpointValidator(Function<String, InetAddress[]> resolver) {
        this.resolver = resolver;
    }

    /** JDK解析错误转为无原域名的固定异常，调用方统一返回依赖不可用。 */
    private static InetAddress[] resolveAddresses(String hostname) {
        try {
            return InetAddress.getAllByName(hostname);
        } catch (UnknownHostException failure) {
            throw new IllegalStateException("DNS resolution failed");
        }
    }

    /** 关闭当前Core创建的解析线程池，不影响其它组件。 */
    @PreDestroy
    void close() {
        lookups.shutdownNow();
    }

    /**
     * 字节形式网络前缀，避免IP字符串包含关系或域名解析参与CIDR判断。
     */
    private record Network(byte[] address, int prefix) {
        private boolean contains(byte[] candidate) {
            if (candidate.length != address.length) {
                return false;
            }
            for (int bit = 0; bit < prefix; bit++) {
                int mask = 1 << (7 - bit % 8);
                if ((candidate[bit / 8] & mask) != (address[bit / 8] & mask)) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * 仅解析严格数字IP/CIDR，不允许短IPv4、zone ID、域名或零前缀。
     */
    private static Network network(String cidr) {
        try {
            String[] parts = cidr.split("/", -1);
            if (parts.length != 2 || !parts[1].matches("[1-9][0-9]{0,2}")) {
                throw ApiException.invalid();
            }
            String literal = parts[0];
            if (literal.indexOf(':') >= 0) {
                if (!literal.matches("[0-9A-Fa-f:]+")) {
                    throw ApiException.invalid();
                }
            } else {
                String[] octets = literal.split("\\.", -1);
                if (octets.length != 4) {
                    throw ApiException.invalid();
                }
                for (String octet : octets) {
                    if (!octet.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(octet) > 255) {
                        throw ApiException.invalid();
                    }
                }
            }
            byte[] bytes = InetAddress.getByName(literal).getAddress();
            int prefix = Integer.parseInt(parts[1]);
            if (prefix > bytes.length * 8) {
                throw ApiException.invalid();
            }
            for (int bit = prefix; bit < bytes.length * 8; bit++) {
                bytes[bit / 8] &= (byte) ~(1 << (7 - bit % 8));
            }
            return new Network(bytes, prefix);
        } catch (UnknownHostException | NumberFormatException failure) {
            throw ApiException.invalid();
        }
    }

    /**
     * 归一化允许的私网CIDR，整个范围必须落在RFC1918或IPv6 ULA内。
     *
     * @param cidr 原CIDR
     * @return 规范数字网络前缀
     */
    public static String privateCidr(String cidr) {
        Network candidate = network(cidr);
        boolean valid = PRIVATE_RANGES.stream().map(CatalogEndpointValidator::network)
            .anyMatch(range -> candidate.prefix() >= range.prefix() && range.contains(candidate.address()));
        if (!valid) {
            throw ApiException.invalid();
        }
        try {
            return InetAddress.getByAddress(candidate.address()).getHostAddress() + "/" + candidate.prefix();
        } catch (UnknownHostException failure) {
            throw ApiException.invalid();
        }
    }

    /**
     * 检查最终解析地址；混合公网/危险地址不能只挑安全的一个放行。
     */
    private boolean permitted(InetAddress address, JsonNode policy, String mode) {
        byte[] bytes = address.getAddress();
        if (FORBIDDEN_RANGES.stream().map(CatalogEndpointValidator::network).anyMatch(range -> range.contains(bytes))) {
            return false;
        }
        boolean privateAddress = PRIVATE_RANGES.stream().map(CatalogEndpointValidator::network)
            .anyMatch(range -> range.contains(bytes));
        if (privateAddress) {
            if (!"SELF_HOSTED".equals(mode) || !policy.get("allowPrivateNetwork").booleanValue()) {
                return false;
            }
            for (JsonNode cidr : policy.get("approvedPrivateCidrs")) {
                if (network(cidr.asString()).contains(bytes)) {
                    return true;
                }
            }
            return false;
        }
        // IPv6只接受全局单播2000::/3；其它未明确批准范围默认拒绝。
        return bytes.length == 4 || network("2000::/3").contains(bytes);
    }

    /**
     * 检查URL语法、精确主机/端口及本次全部DNS答案，不发送HTTP或跟随重定向。
     *
     * @param endpoint 管理员登记的完整端点
     * @param policy 已封闭校验的HostPolicy
     * @param mode 当前托管模式
     * @return 规范HTTPS端点
     */
    public String check(String endpoint, JsonNode policy, String mode) {
        // 1、拒绝解析歧义、秘密查询参数和IP字面量，只接受准确FQDN。
        URI uri;
        try {
            uri = URI.create(endpoint);
        } catch (IllegalArgumentException failure) {
            throw ApiException.invalid();
        }
        String path = uri.getRawPath();
        if (endpoint.length() > 2048 || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
            || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
            || path == null || !path.startsWith("/") || path.contains("%") || path.contains("\\")
            || path.contains("//") || Arrays.stream(path.split("/", -1))
                .anyMatch(part -> ".".equals(part) || "..".equals(part))) {
            throw ApiException.invalid();
        }
        String host = CatalogValidation.hostname(uri.getHost());
        int port = uri.getPort() == -1 ? 443 : uri.getPort();
        boolean hostAllowed = false;
        for (JsonNode value : policy.get("allowedHostnames")) {
            hostAllowed |= host.equals(value.asString());
        }
        boolean portAllowed = false;
        for (JsonNode value : policy.get("allowedPorts")) {
            portAllowed |= port == value.intValue();
        }
        if (!hostAllowed || !portAllowed || port < 1 || port > 65535) {
            throw ApiException.invalid();
        }

        // 2、解析等待有界，失败时不猜测地址或沿用上一请求的安全结论。
        Future<InetAddress[]> lookup;
        try {
            lookup = lookups.submit(() -> resolver.apply(host));
        } catch (RejectedExecutionException failure) {
            throw new ApiException(503, "DEPENDENCY_UNAVAILABLE", "Endpoint resolution is unavailable");
        }
        try {
            InetAddress[] addresses = lookup.get(2, TimeUnit.SECONDS);
            if (addresses == null || addresses.length == 0) {
                throw new ApiException(503, "DEPENDENCY_UNAVAILABLE", "Endpoint resolution is unavailable");
            }
            for (InetAddress address : addresses) {
                if (address == null || !permitted(address, policy, mode)) {
                    throw ApiException.invalid();
                }
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new ApiException(503, "DEPENDENCY_UNAVAILABLE", "Endpoint resolution is unavailable");
        } catch (ExecutionException | TimeoutException failure) {
            throw new ApiException(503, "DEPENDENCY_UNAVAILABLE", "Endpoint resolution is unavailable");
        } finally {
            lookup.cancel(true);
        }
        return "https://" + host + (port == 443 ? "" : ":" + port) + path;
    }
}
