package com.felix.aigategateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Gateway 应用启动入口，只负责公开入口边界和到 Core 的受控转发。
 *
 * @author AIGate
 */
@SpringBootApplication
public class AigateGatewayApplication {

    /**
     * 启动当前应用或独立任务，敏感配置不通过命令行参数传递。
     *
     * @param args 应用启动参数，不应包含密钥明文
     */
    public static void main(String[] args) {
        // 启动 Gateway，目标地址和服务凭证从运行配置读取。
        SpringApplication.run(AigateGatewayApplication.class, args);
    }
}
