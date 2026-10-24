package com.felix.aigatecore.security;

import com.aigate.contracts.ApiContractConstants;
import com.aigate.contracts.ApiError;
import com.aigate.contracts.RequestIds;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Core 服务身份边界，验证受信 Gateway 凭证，设置安全请求 ID，并限制固定长度和分块请求体的大小。
 *
 * @author AIGate
 */
@Component
public class CoreBoundaryFilter extends OncePerRequestFilter {

    private final SecuritySettings settings;

    private final ObjectMapper json;

    /**
     * 创建CoreBoundaryFilter，显式注入当前组件依赖或不可变状态。
     *
     * @param settings 服务身份、Bootstrap 边界与轮换配置
     * @param json 负责安全 JSON 序列化的映射器
     */
    public CoreBoundaryFilter(SecuritySettings settings, ObjectMapper json) {
        this.settings = settings;
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
        String requestId = RequestIds.safe(request.getHeader("X-Request-Id"));
        request.setAttribute("requestId", requestId);
        response.setHeader("X-Request-Id", requestId);
        response.setHeader("Cache-Control", "no-store");
        var headers = Collections.list(request.getHeaders("X-AIGate-Service-Authorization"));
        if (headers.size() != 1 || !settings.validService(headers.getFirst())) {
            response.setStatus(401);
            response.setContentType("application/json");
            response.getWriter().write(json.writeValueAsString(
                ApiError.of("INTERNAL_UNAUTHENTICATED", "Invalid service credentials", requestId, false)));
            return;
        }
        if (request.getContentLengthLong() > ApiContractConstants.MAX_JSON_BODY_BYTES) {
            response.setStatus(400);
            response.setContentType("application/json");
            response.getWriter()
                .write(json.writeValueAsString(ApiError.of("VALIDATION_ERROR", "Invalid request", requestId, false)));
            return;
        }
        var bounded = new HttpServletRequestWrapper(request) {

            private final ServletInputStream source = request.getInputStream();

            private final ServletInputStream limited = new ServletInputStream() {

                private int count;

                @Override
                public int read() throws IOException {
                    int value = source.read();
                    if (value >= 0 && ++count > ApiContractConstants.MAX_JSON_BODY_BYTES) {
                        throw new IOException("Request too large");
                    }
                    return value;
                }

                @Override
                public int read(byte[] bytes, int offset, int length) throws IOException {
                    if (length == 0) {
                        return 0;
                    }
                    int read = source.read(bytes, offset,
                        Math.min(length, ApiContractConstants.MAX_JSON_BODY_BYTES + 1 - count));
                    if (read > 0 && (count += read) > ApiContractConstants.MAX_JSON_BODY_BYTES) {
                        throw new IOException("Request too large");
                    }
                    return read;
                }

                @Override
                public boolean isFinished() {
                    return source.isFinished();
                }

                @Override
                public boolean isReady() {
                    return source.isReady();
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    source.setReadListener(listener);
                }
            };

            @Override
            public ServletInputStream getInputStream() {
                return limited;
            }

            @Override
            public BufferedReader getReader() {
                return new BufferedReader(new InputStreamReader(limited, StandardCharsets.UTF_8));
            }
        };
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("gateway", null, List.of()));
        SecurityContextHolder.setContext(context);
        try {
            chain.doFilter(bounded, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
