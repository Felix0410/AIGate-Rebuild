package com.felix.aigatecore;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Core 应用启动入口，负责身份、权限、审计和数据库事实。
 *
 * @author AIGate
 */
@SpringBootApplication
public class AigateCoreApplication {

    /**
     * 启动当前应用或独立任务，敏感配置不通过命令行参数传递。
     *
     * @param args 应用启动参数，不应包含密钥明文
     */
    public static void main(String[] args) {
        SpringApplication.run(AigateCoreApplication.class, args);
    }
}
