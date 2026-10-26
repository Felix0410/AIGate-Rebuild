package com.felix.aigategateway.security;

import com.aigate.contracts.ApiContractConstants;
import com.aigate.contracts.ApiError;
import com.aigate.contracts.ManagementRouteCatalog;
import com.aigate.contracts.RequestIds;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import tools.jackson.databind.ObjectMapper;

/**
 * 公开入口边界，在网络转发前检查精确路由、方法及可预先确定的请求体长度。 检查入口路径、方法和正文大小；员工权限由 Core 验证。
 *
 * @author AIGate
 */
@Component
public class GatewayBoundaryFilter extends OncePerRequestFilter {

    // Spring Boot 注入的 ObjectMapper，用于生成错误响应 JSON。
    private final ObjectMapper json;

    /**
     * 通过构造器注入 Spring Boot 提供的 JSON 转换工具。
     *
     * @param json 负责安全 JSON 序列化的映射器
     */
    public GatewayBoundaryFilter(ObjectMapper json) {
        this.json = json;
    }

    /**
     * 执行本服务的请求边界检查；拒绝响应不回显原始凭证或内部地址。
     *
     * @param request 当前 HTTP 请求
     * @param response 当前 HTTP 响应
     * @param chain 后续 Servlet 过滤链
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        // 1、使用 RequestIds.safe 生成安全请求 ID，写入上下文及响应头。
        String id = RequestIds.safe(request.getHeader("X-Request-Id"));
        request.setAttribute("requestId", id);
        response.setHeader("X-Request-Id", id);
        response.setHeader("Cache-Control", "no-store");

        // 2、使用 ManagementRouteCatalog.rejection 检查路径和方法。
        int status = ManagementRouteCatalog.rejection(request.getMethod(), request.getRequestURI());

        // 3、已知正文超过 64 KiB 时返回 400，分块正文由 Core 继续检查。
        if (request.getContentLengthLong() > ApiContractConstants.MAX_JSON_BODY_BYTES) {
            status = 400;
        }

        // 4、检查不通过时返回统一 JSON 错误，不再进入代理路由。
        if (status != 0) {
            response.setStatus(status);
            response.setContentType("application/json");
            String code = status == 405 ? "METHOD_NOT_ALLOWED" : status == 400 ? "VALIDATION_ERROR" : "NOT_FOUND";
            response.getWriter().write(json.writeValueAsString(ApiError.of(code, "Request not allowed", id, false)));
            return;
        }

        // 5、检查通过后执行后续过滤链。
        chain.doFilter(request, response);
    }
}
