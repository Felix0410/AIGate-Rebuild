package com.aigate.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * 真实 Docker Compose 部署回归，验证独立迁移身份、凭证隔离及 Core 停止后的安全失败与恢复。
 *
 * @author AIGate
 */
class ComposeIT {

    private final Path root = Path.of("..").toAbsolutePath().normalize();

    private final JsonMapper json = JsonMapper.builder().build();

    private final Map<String, String> env = new HashMap<>();

    private final List<String> secrets = new ArrayList<>();

    private final String project = "aigate-p1-it-" + ProcessHandle.current().pid();

    private int sequence;

    private int port;

    /**
     * 生成当前 Compose 测试独占的随机秘密，测试结束后销毁环境。
     *
     * @return 当前操作生成或校验后的文本
     */
    private String secret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 规范化诊断标识或测试输出，防止秘密信息进入日志和报告。
     *
     * @param text 待处理的文本；测试 SQL 仅允许用于隔离数据库
     * @return 当前操作生成或校验后的文本
     */
    private String safe(String text) {
        for (String value : secrets) {
            text = text.replace(value, "[REDACTED]");
        }
        return text.replaceAll("(?:mgt_|bst_|svc_)[A-Za-z0-9_-]{20,}", "[REDACTED]");
    }

    /**
     * 使用独立 Compose 项目执行命令；保存前脱敏，超时后回收命令进程。
     *
     * @param arguments 受控 Docker Compose 命令参数
     * @return 当前操作生成或校验后的文本
     */
    private String compose(String... arguments) throws Exception {
        var args = new ArrayList<>(List.of("docker", "compose", "-p", project, "-f", "infra/compose.ci.yml"));
        args.addAll(List.of(arguments));
        Path raw = Files.createTempFile("aigate-compose-", ".log");
        var builder = new ProcessBuilder(args).directory(root.toFile()).redirectErrorStream(true)
            .redirectOutput(raw.toFile());
        builder.environment().putAll(env);
        try {
            Process process = builder.start();
            if (!process.waitFor(180, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                fail("Compose command timed out");
            }
            String output = Files.readString(raw);
            Files.writeString(Path.of("target", "compose-" + (++sequence) + ".log"), safe(output));
            assertEquals(0, process.exitValue(), () -> safe(output));
            return output;
        } finally {
            Files.deleteIfExists(raw);
        }
    }

    /**
     * 向测试 Gateway 发送请求，由真实 Core 执行认证和业务事务。
     *
     * @param method 待匹配的 HTTP 方法
     * @param path 已限定到测试服务或公开合同的请求路径
     * @param token 待校验的原始凭证，只允许在当前调用内使用
     * @param body 当前接口字段白名单下的已校验正文
     * @param idem 当前测试独占的业务幂等键
     * @return 真实 HTTP 调用返回的状态、响应头与正文
     */
    private HttpResponse<String> call(String method, String path, String token, String body, String idem)
        throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(15));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        if (idem != null) {
            request.header("Idempotency-Key", idem);
        }
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        try (var client = HttpClient.newHttpClient()) {
            return client.send(request
                .method(method,
                    body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    /**
     * 在限定次数内等待 Compose 应用返回预期状态。
     *
     * @param key 字段名或当前操作的受控幂等标识；凭证明文只允许在首次签发响应使用
     * @param status 当前合同允许的状态值
     */
    private void await(String key, int status) throws Exception {
        for (int i = 0; i < 120; i++) {
            try {
                if (call("GET", "/api/v1/me/profile", key, null, null).statusCode() == status) {
                    return;
                }
            } catch (IOException ignored) {
                // 启动探测允许短暂连接失败；外层截止时间负责终止等待并报告失败。
            }
            Thread.sleep(500);
        }
        fail("Compose application did not reach expected HTTP status");
    }

    /**
     * 验证真实容器部署、独立迁移账号及 Core 停止与恢复。
     */
    @Test
    @DisplayName("验证真实容器部署、独立迁移账号及 Core 停止与恢复。")
    void isolatedComposeMigrationAccountsAndFailureRecovery() throws Exception {
        Files.createDirectories(Path.of("target"));
        try (var socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        for (String name : List.of("MYSQL_ROOT_PASSWORD", "AIGATE_DB_PASSWORD", "AIGATE_FLYWAY_PASSWORD")) {
            env.put(name, secret());
        }
        env.put("AIGATE_SERVICE_TOKEN", "svc_" + secret());
        env.put("AIGATE_BOOTSTRAP_TOKEN", "bst_" + secret());
        env.put("AIGATE_MANAGEMENT_PEPPER",
            Base64.getEncoder().encodeToString(Base64.getUrlDecoder().decode(secret())));
        env.put("AIGATE_BOOTSTRAP_MODE", "INIT");
        env.put("AIGATE_BOOTSTRAP_ALLOWED_CIDRS", "127.0.0.1/32,172.16.0.0/12,192.168.0.0/16");
        env.put("AIGATE_CI_PORT", Integer.toString(port));
        env.put("MYSQL_PWD", env.get("MYSQL_ROOT_PASSWORD"));
        secrets.addAll(env.values().stream().filter(v -> v.length() > 20).toList());
        try {
            var config = json.readTree(compose("config", "--format", "json")).get("services");
            var gatewayEnv = config.get("gateway").get("environment");
            assertFalse(gatewayEnv.has("AIGATE_DB_PASSWORD"));
            assertFalse(gatewayEnv.has("AIGATE_MANAGEMENT_PEPPER"));
            assertFalse(config.get("core").has("ports"));
            assertFalse(config.get("mysql").has("ports"));
            assertFalse(config.get("core").get("environment").has("AIGATE_FLYWAY_PASSWORD"));
            compose("up", "-d", "--build");
            await(null, 401);
            String body = json.writeValueAsString(Map.of("teamName", "Compose Platform", "employeeCode",
                "COMPOSE_ADMIN", "displayName", "Compose Administrator", "managementKeyExpiresAt",
                Instant.now().plusSeconds(86400).truncatedTo(ChronoUnit.MICROS).toString()));
            var initialized = call("POST", "/api/v1/bootstrap/initialize", env.get("AIGATE_BOOTSTRAP_TOKEN"), body,
                "compose-bootstrap");
            assertEquals(201, initialized.statusCode(), () -> safe(initialized.body()));
            String key = json.readTree(initialized.body()).get("managementCredential").get("key").asString();
            secrets.add(key);
            assertEquals(200, call("GET", "/api/v1/me/profile", key, null, null).statusCode());
            assertEquals(404, call("GET", "/internal/v1/ping", key, null, null).statusCode());
            compose("stop", "core");
            var unavailable = call("GET", "/api/v1/me/profile", key, null, null);
            assertTrue(unavailable.statusCode() == 503 || unavailable.statusCode() == 504);
            assertTrue(json.readTree(unavailable.body()).has("error"));
            assertFalse(json.readTree(unavailable.body()).get("error").get("retryable").asBoolean());
            compose("start", "core");
            await(key, 200);
            assertEquals(409,
                call("POST", "/api/v1/bootstrap/initialize", env.get("AIGATE_BOOTSTRAP_TOKEN"), body, "compose-restart")
                    .statusCode());
            compose("run", "--rm", "migrate");
            String history = compose("exec", "-T", "-e", "MYSQL_PWD", "mysql", "mysql", "-uroot", "-Nse",
                "SELECT COUNT(*) FROM core_db.flyway_schema_history WHERE success=1");
            assertEquals("1", history.strip());
            compose("logs", "--no-color");
        } finally {
            try {
                compose("logs", "--no-color");
            } finally {
                compose("down", "-v", "--remove-orphans");
            }
        }
    }
}
