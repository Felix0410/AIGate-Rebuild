package com.felix.aigatecore.security;

import com.aigate.contracts.ApiError;

import jakarta.servlet.DispatcherType;

import java.util.UUID;
import java.time.Clock;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.firewall.RequestRejectedHandler;

import tools.jackson.databind.ObjectMapper;

/**
 * 无状态安全过滤链配置；服务身份或路由边界不能被会话、默认账号或表单登录绕过。
 *
 * @author AIGate
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    /**
     * 提供统一 UTC 时钟，测试可以替换为固定时钟校验时间边界。
     *
     * @return 系统 UTC 时钟
     */
    @Bean
    Clock runtimeClock() {
        return Clock.systemUTC();
    }

    /**
     * 统一映射安全防火墙拒绝请求，输出带安全请求 ID 的参数错误。
     *
     * @param json 负责安全 JSON 序列化的映射器
     * @return 输出统一安全错误结构的防火墙拒绝处理器
     */
    @Bean
    RequestRejectedHandler requestRejectedHandler(ObjectMapper json) {
        return (request, response, failure) -> {
            // 为防火墙拒绝的请求生成 ID，返回 400 JSON 响应并禁止缓存。
            String id = UUID.randomUUID().toString();
            response.setStatus(400);
            response.setContentType("application/json");
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("X-Request-Id", id);
            response.getWriter()
                .write(json.writeValueAsString(ApiError.of("VALIDATION_ERROR", "Invalid request", id, false)));
        };
    }

    /**
     * 禁用 Spring 默认随机账号；身份只由显式的服务及管理凭证流程处理。
     *
     * @return 始终拒绝默认账号查询的用户服务
     */
    @Bean
    UserDetailsService userDetailsService() {
        // 提供空用户服务，关闭 Spring Boot 默认账号的自动创建。
        return new InMemoryUserDetailsManager();
    }

    /**
     * 防止同一安全过滤器同时注册到 Servlet 容器和 Spring Security 链而执行两次。
     *
     * @param filter 当前服务的安全边界过滤器
     * @return 已禁用 Servlet 容器重复注册的过滤器配置
     */
    @Bean
    FilterRegistrationBean<CoreBoundaryFilter> disableContainerRegistration(CoreBoundaryFilter filter) {
        // 关闭 Servlet 自动注册，过滤器只在 Spring Security 链中执行一次。
        var registration = new FilterRegistrationBean<>(filter);

        registration.setEnabled(false);

        return registration;
    }

    /**
     * 构造无状态过滤链，不启用表单登录、会话认证或透明业务重试。
     *
     * @param http Spring Security 过滤链构建器
     * @param filter 当前服务的安全边界过滤器
     * @return 不建立会话的服务安全过滤链
     */
    @Bean
    SecurityFilterChain security(HttpSecurity http, CoreBoundaryFilter filter) throws Exception {
        // 关闭 CSRF、会话和请求缓存，将边界过滤器放在授权过滤器之前。
        return http.csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(cache -> cache.disable())
            // 允许容器内部 ERROR 分派保留原错误，直接访问 /error 仍需服务认证。
            .authorizeHttpRequests(
                auth -> auth.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll().anyRequest().authenticated())
            .addFilterBefore(filter, AuthorizationFilter.class).build();
    }
}
