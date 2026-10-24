package com.aigate.contracts;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * P1 公开路由合同，按精确路径和 HTTP 方法枚举，禁止使用透传所有路径的兜底路由。
 *
 * @author AIGate
 */
public final class P1Routes {

    /**
     * 工具类不允许实例化。
     */
    private P1Routes() {
    }

    /**
     * 一个公开资源路径及其允许的方法集合，路径中的占位符仅匹配单个路径段。
     *
     * @param path 已限定到测试服务或公开合同的请求路径
     * @param methods 当前路径允许的 HTTP 方法集合
     * @author AIGate
     */
    public record Route(String path, Set<String> methods) {

        /**
         * 按合同模板匹配单个公开路径，禁止占位符吞掉多层路径。
         *
         * @param candidate 待规范化的路径或诊断标识
         * @return 满足方法所述条件时为 true，否则为 false
         */
        public boolean matches(String candidate) {
            return Pattern.matches(path.replaceAll("\\{[^}]+}", "[^/]+"), candidate);
        }
    }

    public static final List<Route> ALL = List.of(new Route("/api/v1/bootstrap/initialize", Set.of("POST")),
        new Route("/api/v1/bootstrap/recover-management-key", Set.of("POST")),
        new Route("/api/v1/teams", Set.of("GET", "POST")), new Route("/api/v1/teams/{teamId}", Set.of("GET", "PATCH")),
        new Route("/api/v1/employees", Set.of("GET", "POST")),
        new Route("/api/v1/employees/{employeeId}", Set.of("GET", "PATCH")),
        new Route("/api/v1/employees/{employeeId}/role-assignments/{roleCode}/{scopeType}/{scopeId}",
            Set.of("PUT", "DELETE")),
        new Route("/api/v1/employees/{employeeId}/management-credentials", Set.of("GET", "POST")),
        new Route("/api/v1/employees/{employeeId}/management-credentials/{credentialId}", Set.of("DELETE")),
        new Route("/api/v1/me/profile", Set.of("GET")), new Route("/api/v1/admin/audit-events", Set.of("GET")));

    /**
     * 识别编码路径、路径穿越和分号等可能绕过白名单的路径形式。
     *
     * @param path 已限定到测试服务或公开合同的请求路径
     * @return 满足方法所述条件时为 true，否则为 false
     */
    public static boolean unsafePath(String path) {
        return path.indexOf('%') >= 0 || path.indexOf(';') >= 0 || path.indexOf('\\') >= 0 || path.contains("//")
            || path.contains("/../") || path.contains("/./");
    }

    /**
     * 判断方法与路径是否可进入代理；返回 0 表示允许，其他值为拒绝的 HTTP 状态。
     *
     * @param method 待匹配的 HTTP 方法
     * @param path 已限定到测试服务或公开合同的请求路径
     * @return 按当前合同校验或计算后的数值
     */
    public static int rejection(String method, String path) {
        if (unsafePath(path)) {
            return 404;
        }
        for (Route route : ALL) {
            if (route.matches(path)) {
                return route.methods().contains(method) ? 0 : 405;
            }
        }
        return 404;
    }
}
