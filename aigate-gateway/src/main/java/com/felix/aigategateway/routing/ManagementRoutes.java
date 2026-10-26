package com.felix.aigategateway.routing;

import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.uri;
import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;

import com.aigate.contracts.ApiError;
import com.aigate.contracts.P1Routes;

import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.function.RequestPredicates;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * Gateway 到 Core 的代理配置，清除客户端伪造的内部身份头，写请求不进行透明重试。
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

        // 1、服务凭证必须以 svc_ 开头，后接 43 个字母、数字、短横线或下划线。
        if (!serviceToken.matches("svc_[A-Za-z0-9_-]{43}")) {
            throw new IllegalStateException("A strong service credential is required");
        }

        // 2、解析 Core 地址，要求为 HTTP(S) 基础地址，且不能包含账号、查询参数、片段或业务路径。
        URI target = URI.create(coreUrl);

        if (!("http".equals(target.getScheme()) || "https".equals(target.getScheme())) || target.getHost() == null
            || target.getUserInfo() != null || target.getQuery() != null || target.getFragment() != null
            || !(target.getPath().isEmpty() || target.getPath().equals("/"))) {
            throw new IllegalStateException("Invalid Core base URL");
        }

        // 3、按公开路径和 HTTP 方法逐项注册路由。
        var routes = route("p1-management");
        for (var entry : P1Routes.ALL) {
            for (String method : entry.methods()) {
                routes.route(
                    RequestPredicates.method(HttpMethod.valueOf(method)).and(RequestPredicates.path(entry.path())),
                    http());
            }
        }

        // 4、在发送代理请求前设置 Core 目标地址，清理客户端请求头并写入可信请求头。
        return routes.before(uri(coreUrl)).before(request -> ServerRequest.from(request).headers(headers -> {
            // 1、遍历头名称副本，删除客户端传入的代理链和内部身份头。
            for (String name : new ArrayList<>(headers.headerNames())) {

                // HTTP 头不区分大小写，转换为小写后匹配。
                String lower = name.toLowerCase(Locale.ROOT);

                if (lower.equals("forwarded") || lower.startsWith("x-forwarded-") || lower.startsWith("x-internal-")
                    || lower.startsWith("x-authenticated-") || lower.startsWith("x-aigate-")
                    || lower.equals("x-employee-id")) {
                    headers.remove(name);
                }
            }

            // 2、写入 Gateway 服务凭证，用户 Authorization 保留给 Core 验证。
            headers.set("X-AIGate-Service-Authorization", "Bearer " + serviceToken);

            // 3、写入当前连接的客户端地址，不使用客户端自报的来源头。
            headers.set("X-AIGate-Client-Address", request.servletRequest().getRemoteAddr());

            // 4、写入入口过滤器已生成的安全请求 ID。
            headers.set("X-Request-Id", (String) request.servletRequest().getAttribute("requestId"));
        }).build()).filter((request, next) -> {
            try {
                // 1、执行后续代理调用，等待 Core 响应；失败时不自动重试写请求。
                return next.handle(request);
            } catch (Exception failure) {

                // 2、记录失败请求的 ID 和异常类型，不输出凭证或内部地址。
                LOGGER.warn("Core 转发失败，requestId={}，exceptionType={}",
                    request.servletRequest().getAttribute("requestId"), failure.getClass().getName());

                // 3、沿异常原因链查找超时，超时返回 504，其他转发异常返回 503。
                boolean timeout = false;
                for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                    if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException) {
                        timeout = true;
                    }
                }

                // 4、返回统一错误并禁止缓存；retryable 为 false，避免重复提交写操作。
                return ServerResponse.status(timeout ? 504 : 503).header("Cache-Control", "no-store")
                    .body(ApiError.of(timeout ? "UPSTREAM_TIMEOUT" : "DEPENDENCY_UNAVAILABLE",
                        "The operation could not be completed safely",
                        (String) request.servletRequest().getAttribute("requestId"), false));
            }
        }).build();
    }
}
