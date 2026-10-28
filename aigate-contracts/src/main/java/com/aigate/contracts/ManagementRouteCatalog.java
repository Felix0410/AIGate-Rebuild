package com.aigate.contracts;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 公开路由列表，逐项定义允许访问的路径和 HTTP 方法。
 *
 * @author AIGate
 */
public final class ManagementRouteCatalog {

    /**
     * 工具类不允许实例化。
     */
    private ManagementRouteCatalog() {
    }

    /**
     * 一条公开路径及其允许的 HTTP 方法，路径占位符只匹配单段。
     *
     * @param path 待检查的请求路径
     * @param methods 当前路径允许的 HTTP 方法集合
     * @author AIGate
     */
    public record Route(String path, Set<String> methods) {

        /**
         * 按路由模板匹配完整请求路径，占位符不能包含斜杠。
         *
         * @param candidate 待规范化的路径或诊断标识
         * @return 完整路径与模板匹配时为 true
         */
        public boolean matches(String candidate) {
            // 将路径占位符替换为单段匹配，要求整个请求路径匹配。
            return Pattern.matches(path.replaceAll("\\{[^}]+}", "[^/]+"), candidate);
        }
    }

    // Gateway 的路由注册和入口检查共用这份路径、方法列表。
    public static final List<Route> ALL = List.of(
        new Route("/api/v1/bootstrap/initialize", Set.of("POST")),
        new Route("/api/v1/bootstrap/recover-management-key", Set.of("POST")),
        new Route("/api/v1/teams", Set.of("GET", "POST")),
        new Route("/api/v1/teams/{teamId}", Set.of("GET", "PATCH")),
        new Route("/api/v1/employees", Set.of("GET", "POST")),
        new Route("/api/v1/employees/{employeeId}", Set.of("GET", "PATCH")),
        new Route("/api/v1/employees/{employeeId}/role-assignments/{roleCode}/{scopeType}/{scopeId}",
            Set.of("PUT", "DELETE")),
        new Route("/api/v1/employees/{employeeId}/management-credentials", Set.of("GET", "POST")),
        new Route("/api/v1/employees/{employeeId}/management-credentials/{credentialId}", Set.of("DELETE")),
        new Route("/api/v1/me/profile", Set.of("GET")),
        new Route("/api/v1/admin/audit-events", Set.of("GET")),
        new Route("/api/v1/agents", Set.of("GET", "POST")),
        new Route("/api/v1/agents/{agentId}", Set.of("GET", "PATCH")),
        new Route("/api/v1/agents/{agentId}/keys", Set.of("GET", "POST")),
        new Route("/api/v1/agents/{agentId}/keys/{keyId}", Set.of("DELETE")),
        new Route("/api/v1/agents/{agentId}/keys/{keyId}/rotate", Set.of("POST")),
        new Route("/api/v1/employees/{employeeId}/runtime-credentials", Set.of("GET", "POST")),
        new Route("/api/v1/employees/{employeeId}/runtime-credentials/{credentialId}", Set.of("DELETE")),
        new Route("/api/v1/employees/{employeeId}/runtime-credentials/{credentialId}/rotate", Set.of("POST")),
        new Route("/api/v1/agents/{agentId}/employees", Set.of("GET")),
        new Route("/api/v1/agents/{agentId}/employees/{employeeId}", Set.of("PUT", "DELETE")),
        new Route("/api/v1/providers", Set.of("GET", "POST")),
        new Route("/api/v1/providers/{providerId}", Set.of("GET", "PATCH")),
        new Route("/api/v1/models", Set.of("GET", "POST")),
        new Route("/api/v1/models/{modelId}", Set.of("GET", "PATCH")),
        new Route("/api/v1/models/{modelId}/deployments", Set.of("GET", "POST")),
        new Route("/api/v1/deployments/{deploymentId}", Set.of("GET", "PATCH")),
        new Route("/api/v1/models/{modelId}/points-prices", Set.of("GET", "POST")),
        new Route("/api/v1/models/{modelId}/points-prices/{revision}/publish", Set.of("POST")),
        new Route("/api/v1/deployments/{deploymentId}/purchase-prices", Set.of("GET", "POST")),
        new Route("/api/v1/deployments/{deploymentId}/purchase-prices/{revision}/publish", Set.of("POST")),
        new Route("/api/v1/deployments/{deploymentId}/credentials", Set.of("GET", "POST")),
        new Route("/api/v1/deployments/{deploymentId}/credentials/{credentialId}", Set.of("DELETE")),
        new Route("/api/v1/agents/{agentId}/models", Set.of("GET")),
        new Route("/api/v1/agents/{agentId}/models/{modelId}", Set.of("PUT", "DELETE")),
        new Route("/api/v1/quotas/policies", Set.of("GET", "POST")),
        new Route("/api/v1/quotas/policies/{policyId}/publish", Set.of("POST")),
        new Route("/api/v1/employees/{employeeId}/quotas", Set.of("GET")),
        new Route("/api/v1/provider-budgets", Set.of("GET")),
        new Route("/api/v1/provider-budgets/{currency}/{month}", Set.of("GET", "PUT")),
        new Route("/api/v1/finance-operations/{operationKey}", Set.of("GET")),
        new Route("/api/v1/teams/{teamId}/pool/{periodStart}", Set.of("PUT")),
        new Route("/api/v1/teams/{teamId}/pool", Set.of("GET")),
        new Route("/api/v1/teams/{teamId}/grants", Set.of("GET", "POST")),
        new Route("/api/v1/teams/{teamId}/grants/{grantId}/revoke", Set.of("POST")),
        new Route("/api/v1/employees/me/grants", Set.of("GET")),
        new Route("/api/v1/governance/policies/drafts", Set.of("POST")),
        new Route("/api/v1/governance/policies/{revisionId}/submit", Set.of("POST")),
        new Route("/api/v1/governance/policies/{revisionId}/review", Set.of("POST")),
        new Route("/api/v1/governance/policies/{revisionId}/publish", Set.of("POST")),
        new Route("/api/v1/governance/policies/active", Set.of("GET")),
        new Route("/api/v1/governance/agent-egress-approvals", Set.of("GET", "POST")),
        new Route("/api/v1/governance/deployment-boundaries/{deploymentId}", Set.of("PUT")),
        new Route("/api/v1/governance/exceptions", Set.of("POST")),
        new Route("/api/v1/governance/exceptions/{requestId}/review", Set.of("POST")),
        new Route("/api/v1/governance/exceptions/{requestId}/revoke", Set.of("POST")),
        new Route("/api/v1/governance/decisions", Set.of("GET")));

    /**
     * 识别编码路径、路径穿越和分号等可能绕过白名单的路径形式。
     *
     * @param path 待检查的请求路径
     * @return 包含不允许的路径形式时为 true
     */
    public static boolean unsafePath(String path) {
        // 检查编码字符、分号、反斜杠及目录跳转等可能产生歧义的路径。
        return path.indexOf('%') >= 0 || path.indexOf(';') >= 0 || path.indexOf('\\') >= 0 || path.contains("//")
            || path.contains("/../") || path.contains("/./");
    }

    /**
     * 判断方法与路径是否可进入代理；返回 0 表示允许，其他值为拒绝的 HTTP 状态。
     *
     * @param method 待匹配的 HTTP 方法
     * @param path 待检查的请求路径
     * @return 允许时为 0；方法不支持时为 405；其他情况为 404
     */
    public static int rejection(String method, String path) {
        // 1、先拒绝可能产生歧义的路径。
        if (unsafePath(path)) {
            return 404;
        }

        // 2、匹配公开路径和方法，允许时返回 0，路径存在但方法不支持时返回 405。
        for (Route route : ALL) {
            if (route.matches(path)) {
                return route.methods().contains(method) ? 0 : 405;
            }
        }

        // 3、没有匹配路径时返回 404。
        return 404;
    }
}
