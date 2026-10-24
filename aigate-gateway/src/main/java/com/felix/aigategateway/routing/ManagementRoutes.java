package com.felix.aigategateway.routing;

import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.uri;
import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;
import com.aigate.contracts.ApiError;
import com.aigate.contracts.P1Routes;
import java.net.SocketTimeoutException;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.function.RequestPredicates;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * P1 到 Core 的受控代理配置，清除客户端伪造的内部身份头，写请求不进行透明重试。
 *
 * @author AIGate
 */
@Configuration(proxyBeanMethods = false)
public class ManagementRoutes {

    private static final Logger LOGGER = LoggerFactory.getLogger(ManagementRoutes.class);

    /**
     * 按公开合同注册精确路由，清除伪造身份头后再附加真实服务凭证。
     *
     * @param coreUrl 配置的 Core 基础地址，不允许客户端覆盖
     * @param serviceToken 当前独立服务凭证，禁止记录其原文
     * @return 仅包含合同白名单路径和方法的代理路由
     */
    @Bean
    RouterFunction<ServerResponse> managementRouter(@Value("${AIGATE_CORE_URL:http://127.0.0.1:8081}") String coreUrl,
        @Value("${AIGATE_SERVICE_TOKEN}") String serviceToken) {
        if (!serviceToken.matches("svc_[A-Za-z0-9_-]{43}")) {
            throw new IllegalStateException("A strong service credential is required");
        }
        URI target = URI.create(coreUrl);
        if (!("http".equals(target.getScheme()) || "https".equals(target.getScheme())) || target.getHost() == null
            || target.getUserInfo() != null || target.getQuery() != null || target.getFragment() != null
            || !(target.getPath().isEmpty() || target.getPath().equals("/"))) {
            throw new IllegalStateException("Invalid Core base URL");
        }
        var routes = route("p1-management");
        for (var entry : P1Routes.ALL) {
            for (String method : entry.methods()) {
                routes.route(
                    RequestPredicates.method(HttpMethod.valueOf(method)).and(RequestPredicates.path(entry.path())),
                    http());
            }
        }
        return routes.before(uri(coreUrl)).before(request -> ServerRequest.from(request).headers(headers -> {
            for (String name : new ArrayList<>(headers.headerNames())) {
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.equals("forwarded") || lower.startsWith("x-forwarded-") || lower.startsWith("x-internal-")
                    || lower.startsWith("x-authenticated-") || lower.startsWith("x-aigate-")
                    || lower.equals("x-employee-id")) {
                    headers.remove(name);
                }
            }
            headers.set("X-AIGate-Service-Authorization", "Bearer " + serviceToken);
            headers.set("X-AIGate-Client-Address", request.servletRequest().getRemoteAddr());
            headers.set("X-Request-Id", (String) request.servletRequest().getAttribute("requestId"));
        }).build()).filter((request, next) -> {
            try {
                return next.handle(request);
            } catch (Exception failure) {
                // 原始代理异常可能包含内部地址或头部，日志只保留安全关联信息。
                LOGGER.warn("Core 转发失败，requestId={}，exceptionType={}",
                    request.servletRequest().getAttribute("requestId"), failure.getClass().getName());
                boolean timeout = false;
                for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                    if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException) {
                        timeout = true;
                    }
                }
                return ServerResponse.status(timeout ? 504 : 503).header("Cache-Control", "no-store")
                    .body(ApiError.of(timeout ? "UPSTREAM_TIMEOUT" : "DEPENDENCY_UNAVAILABLE",
                        "The operation could not be completed safely",
                        (String) request.servletRequest().getAttribute("requestId"), false));
            }
        }).build();
    }
}
