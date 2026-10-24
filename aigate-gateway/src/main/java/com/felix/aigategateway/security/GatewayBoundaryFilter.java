package com.felix.aigategateway.security;

import com.aigate.contracts.ApiContractConstants;
import com.aigate.contracts.ApiError;
import com.aigate.contracts.P1Routes;
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
 * 公开入口边界，在网络转发前检查精确路由、方法及可预先确定的请求体长度。
 *
 * @author AIGate
 */
@Component
public class GatewayBoundaryFilter extends OncePerRequestFilter {

    private final ObjectMapper json;

    /**
     * 创建GatewayBoundaryFilter，显式注入当前组件依赖或不可变状态。
     *
     * @param json 负责安全 JSON 序列化的映射器
     */
    public GatewayBoundaryFilter(ObjectMapper json) {
        this.json = json;
    }

    /**
     * 执行本服务的请求边界检查；拒绝响应不回显原始凭证或内部地址。
     *
     * @param request 当前 Servlet 或函数式请求
     * @param response 当前 HTTP 响应
     * @param chain 后续 Servlet 过滤链
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        String id = RequestIds.safe(request.getHeader("X-Request-Id"));
        request.setAttribute("requestId", id);
        response.setHeader("X-Request-Id", id);
        response.setHeader("Cache-Control", "no-store");
        int status = P1Routes.rejection(request.getMethod(), request.getRequestURI());
        if (request.getContentLengthLong() > ApiContractConstants.MAX_JSON_BODY_BYTES) {
            status = 400;
        }
        if (status != 0) {
            response.setStatus(status);
            response.setContentType("application/json");
            String code = status == 405 ? "METHOD_NOT_ALLOWED" : status == 400 ? "VALIDATION_ERROR" : "NOT_FOUND";
            response.getWriter().write(json.writeValueAsString(ApiError.of(code, "Request not allowed", id, false)));
            return;
        }
        chain.doFilter(request, response);
    }
}
