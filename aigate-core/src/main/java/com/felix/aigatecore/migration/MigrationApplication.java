package com.felix.aigatecore.migration;

import org.flywaydb.core.Flyway;

/**
 * 独立数据库迁移任务入口，只使用迁移账号，不启动 Web 服务或加载用户认证逻辑。
 *
 * @author AIGate
 */
public final class MigrationApplication {

    /**
     * 工具类不允许实例化。
     */
    private MigrationApplication() {
    }

    /**
     * 启动当前应用或独立任务，敏感配置不通过命令行参数传递。
     *
     * @param args 应用启动参数，不应包含密钥明文
     */
    public static void main(String[] args) {
        Flyway.configure()
            .dataSource(required("AIGATE_DB_URL"), required("AIGATE_FLYWAY_USERNAME"),
                required("AIGATE_FLYWAY_PASSWORD"))
            .locations("classpath:db/migration").cleanDisabled(true).validateOnMigrate(true).load().migrate();
    }

    /**
     * 读取迁移任务必需配置，只在错误中报告缺少的变量名。
     *
     * @param name 必需的迁移环境变量名称
     * @return 当前操作生成或校验后的文本
     */
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing migration setting: " + name);
        }
        return value;
    }
}
