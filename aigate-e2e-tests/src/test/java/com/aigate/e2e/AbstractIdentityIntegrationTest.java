package com.aigate.e2e;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 真实 MySQL 与双 JVM 测试的公共环境。所有清理仅针对临时容器，不读取或修改开发者本地数据库；场景测试复用该环境并各自重置测试数据。
 *
 * @author AIGate
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractIdentityIntegrationTest {

    protected static final String MYSQL_IMAGE = "mysql@sha256:6ea90827b1100f8f2ae306a53"
        + "9f86d2c264a26ed435a2a9f75551dd5c3aeb242";

    protected final Path projectRoot = Path.of("..").toAbsolutePath().normalize();

    protected final Path outputDirectory = Path.of("target").toAbsolutePath();

    protected final JsonMapper json = JsonMapper.builder().build();

    protected final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    protected final List<String> secretValues = new ArrayList<>();

    protected final String rootPassword = random();

    protected final String appPassword = random();

    protected final String migratorPassword = random();

    protected final String serviceToken = "svc_" + random();

    protected final String bootstrapToken = "bst_" + random();

    protected final String pepper = Base64.getEncoder().encodeToString(randomBytes());

    protected MySQLContainer mysql;

    protected Process core;

    protected Process gateway;

    protected int corePort;

    protected int gatewayPort;

    protected String coreUrl;

    protected int logIndex;

    protected String lastCoreMode;

    protected String lastGatewayDestination;

    protected Map<String, String> lastCoreOverrides = Map.of();

    /**
     * 测试客户端收到的响应快照，失败消息输出前必须经过脱敏。
     *
     * @param status 当前合同允许的状态值
     * @param body 当前接口字段白名单下的已校验正文
     * @param headers 受控测试附加请求头，不能包含开发环境真实凭证
     * @author AIGate
     */
    protected record Reply(int status, String body, Map<String, List<String>> headers) {
    }

    /**
     * 测试管理员身份，密钥仅供当前测试进程使用，不写入版本控制或测试报告。
     *
     * @param key 字段名或当前操作的受控幂等标识；凭证明文只允许在首次签发响应使用
     * @param employeeId 目标员工主键
     * @param teamId 目标团队主键
     * @author AIGate
     */
    protected record Session(String key, long employeeId, long teamId) {
    }

    /**
     * 为隔离测试生成 32 字节 CSPRNG 随机材料。
     *
     * @return 当前测试独占的安全随机字节
     */
    protected byte[] randomBytes() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    /**
     * 为当前测试生成随机编码值，禁止写入测试报告。
     *
     * @return 当前操作生成或校验后的文本
     */
    protected String random() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes());
    }

    /**
     * 生成可精确写入 DATETIME(6) 的测试凭证到期时间。
     *
     * @return 当前操作生成或校验后的文本
     */
    protected String expiry() {
        return Instant.now().plus(Duration.ofDays(2)).truncatedTo(ChronoUnit.MICROS).toString();
    }

    /**
     * 规范化诊断标识或测试输出，防止秘密信息进入日志和报告。
     *
     * @param value 待校验或转换的受控输入
     * @return 当前操作生成或校验后的文本
     */
    protected String safe(String value) {
        for (String secret : secretValues) {
            value = value.replace(secret, "[REDACTED]");
        }
        return value.replaceAll("(?:mgt_|bst_|svc_)[A-Za-z0-9_-]{20,}", "[REDACTED]");
    }

    /**
     * 向操作系统申请临时端口，避免接管开发者已经运行的服务。
     *
     * @return 按当前合同校验或计算后的数值
     */
    protected int port() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /**
     * 连接隔离测试容器的管理员会话，不连接项目本机数据库。
     *
     * @return 隔离测试容器的数据库连接，由调用方关闭
     */
    protected Connection db() throws SQLException {
        return DriverManager.getConnection(mysql.getJdbcUrl(), "root", rootPassword);
    }

    /**
     * 只在隔离测试库中执行准备或故障注入 SQL。
     *
     * @param text 待处理的文本；测试 SQL 仅允许用于隔离数据库
     */
    protected void sql(String text) throws SQLException {
        try (Connection db = db();
            var statement = db.createStatement()) {
            statement.execute(text);
        }
    }

    /**
     * 从隔离测试库读取单个数字结果，用于验证持久化事实。
     *
     * @param text 待处理的文本；测试 SQL 仅允许用于隔离数据库
     * @return 按当前合同校验或计算后的数值
     */
    protected long scalar(String text) throws SQLException {
        try (Connection db = db();
            var statement = db.createStatement();
            var result = statement.executeQuery(text)) {
            result.next();
            return result.getLong(1);
        }
    }

    /**
     * 创建隔离 MySQL 及最小权限账号，并启动两个独立应用 JVM。
     */
    @BeforeAll
    protected void infrastructure() throws Exception {
        assertEquals(21, Runtime.version().feature(), "Use JDK21 to run the P1 contract");
        Files.createDirectories(outputDirectory);
        secretValues.addAll(List.of(rootPassword, appPassword, migratorPassword, serviceToken, bootstrapToken, pepper));
        mysql = new MySQLContainer(DockerImageName.parse(MYSQL_IMAGE).asCompatibleSubstituteFor("mysql"))
            .withDatabaseName("core_db").withUsername("root").withPassword(rootPassword).withEnv("TZ", "UTC");
        mysql.start();
        sql("CREATE USER 'aigate_app'@'%' IDENTIFIED BY '" + appPassword + "'");
        sql("CREATE USER 'aigate_migrator'@'%' IDENTIFIED BY '" + migratorPassword + "'");
        sql("GRANT SELECT,INSERT,UPDATE,DELETE ON core_db.* TO 'aigate_app'@'%'");
        sql("GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,DROP,REFERENCES,INDEX,ALTER "
            + "ON core_db.* TO 'aigate_migrator'@'%'");
        sql("CREATE DATABASE finops_db");
        sql("CREATE TABLE finops_db.private_fact(id BIGINT)");
        corePort = port();
        gatewayPort = port();
        coreUrl = "http://127.0.0.1:" + corePort;
        startCore("INIT", Map.of());
        startGateway(coreUrl);
        Files.writeString(outputDirectory.resolve("environment.txt"), "JDK=" + Runtime.version() + "\nMySQL="
            + MYSQL_IMAGE + "\nCore and Gateway: distinct JVMs\nSchema: isolated Testcontainers core_db\n");
    }

    /**
     * 清空本场景的临时数据；仅在配置改变时重启服务，避免重复支付启动成本。
     */
    @BeforeEach
    protected void emptyFixture() throws Exception {
        if (core == null || !core.isAlive() || !"INIT".equals(lastCoreMode) || !lastCoreOverrides.isEmpty()) {
            stop(core);
            startCore("INIT", Map.of());
        }
        if (gateway == null || !gateway.isAlive() || !coreUrl.equals(lastGatewayDestination)) {
            stop(gateway);
            startGateway(coreUrl);
        }
        // 外键检查开关只对当前连接生效，因此隔离测试库清理必须在同一连接中完成。
        try (Connection db = db();
            var statement = db.createStatement()) {
            statement.execute("SET FOREIGN_KEY_CHECKS=0");
            for (String table : List.of("admin_audit_event", "management_credential", "role_assignment", "employee",
                "team", "bootstrap_control")) {
                statement.execute("TRUNCATE TABLE " + table);
            }
            statement.execute(
                "INSERT INTO bootstrap_control(id,version,created_at,updated_at) "
                    + "VALUES(1,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
            statement.execute("SET FOREIGN_KEY_CHECKS=1");
        }
    }

    /**
     * 构造测试 Core 的运行环境，显式隔离开发者的 .env。
     *
     * @param mode 当前运维动作要求的模式
     * @param overrides 当前场景需要覆盖的隔离配置
     * @return 经过约束的单值参数集合
     */
    protected Map<String, String> coreEnvironment(String mode, Map<String, String> overrides) {
        var environment = new HashMap<String, String>();
        environment.put("AIGATE_ENV_FILE", outputDirectory.resolve("no-user-env").toString());
        environment.put("AIGATE_CORE_PORT", Integer.toString(corePort));
        environment.put("AIGATE_DB_URL", mysql.getJdbcUrl() + (mysql.getJdbcUrl().contains("?") ? "&" : "?")
            + "connectionTimeZone=UTC&connectTimeout=2000&socketTimeout=2000");
        environment.put("AIGATE_DB_USERNAME", "aigate_app");
        environment.put("AIGATE_DB_PASSWORD", appPassword);
        environment.put("AIGATE_FLYWAY_USERNAME", "aigate_migrator");
        environment.put("AIGATE_FLYWAY_PASSWORD", migratorPassword);
        environment.put("AIGATE_SERVICE_TOKEN", serviceToken);
        environment.put("AIGATE_MANAGEMENT_PEPPER", pepper);
        environment.put("AIGATE_BOOTSTRAP_MODE", mode);
        environment.put("AIGATE_BOOTSTRAP_TOKEN", bootstrapToken);
        environment.put("AIGATE_BOOTSTRAP_ALLOWED_CIDRS", "127.0.0.1/32");
        environment.putAll(overrides);
        return environment;
    }

    /**
     * 启动独立测试 JVM，清除继承环境中的真实项目凭证。
     *
     * @param module 当前测试需要启动的应用模块
     * @param environment 当前子进程专用环境
     * @return 当前测试创建的子进程句柄
     */
    protected Process process(String module, Map<String, String> environment) throws IOException {
        Path jar = projectRoot.resolve(module + "/target/" + module + "-0.1.0-SNAPSHOT.jar");
        assertTrue(Files.exists(jar), "Build the full reactor before running integration tests");
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar",
            jar.toString()).directory(projectRoot.toFile());
        builder.environment().keySet().removeIf(key -> key.startsWith("AIGATE_") || key.startsWith("MYSQL_"));
        builder.environment().putAll(environment);
        return builder.redirectErrorStream(true)
            .redirectOutput(outputDirectory.resolve(module + "-" + (++logIndex) + ".log").toFile()).start();
    }

    /**
     * 启动测试 Core 并等待内部服务探针通过。
     *
     * @param mode 当前运维动作要求的模式
     * @param overrides 当前场景需要覆盖的隔离配置
     */
    protected void startCore(String mode, Map<String, String> overrides) throws Exception {
        lastCoreMode = mode;
        lastCoreOverrides = Map.copyOf(overrides);
        core = process("aigate-core", coreEnvironment(mode, overrides));
        waitFor(core, corePort, "/internal/v1/ping", null,
            Map.of("X-AIGate-Service-Authorization", "Bearer " + serviceToken), 200);
    }

    /**
     * 启动测试 Gateway，只注入目标地址和服务凭证，不注入数据库秘密。
     *
     * @param destination 测试 Gateway 的 Core 地址
     */
    protected void startGateway(String destination) throws Exception {
        lastGatewayDestination = destination;
        gateway = process("aigate-gateway", Map.of("AIGATE_GATEWAY_PORT", Integer.toString(gatewayPort),
            "AIGATE_CORE_URL", destination, "AIGATE_SERVICE_TOKEN", serviceToken));
        waitFor(gateway, gatewayPort, "/api/v1/me/profile", null, Map.of(), 401);
    }

    /**
     * 在限定重试次数内等待测试服务就绪，失败时指向脱敏日志。
     *
     * @param process 仅由本测试创建的子进程
     * @param port 测试服务的临时端口
     * @param path 已限定到测试服务或公开合同的请求路径
     * @param token 待校验的原始凭证，只允许在当前调用内使用
     * @param headers 受控测试附加请求头，不能包含开发环境真实凭证
     * @param expected 当前场景期望的响应状态
     */
    protected void waitFor(Process process, int port, String path, String token, Map<String, String> headers,
        int expected) throws Exception {
        for (int i = 0; i < 150; i++) {
            assertTrue(process.isAlive(), "Service exited during startup; see redacted target logs");
            try {
                if (request(port, "GET", path, token, null, headers).status() == expected) {
                    return;
                }
            } catch (IOException ignored) {
                // 启动探测允许短暂连接失败；外层截止时间负责终止等待并报告失败。
            }
            Thread.sleep(200);
        }
        fail("Service startup timed out; see redacted target logs");
    }

    /**
     * 只关闭本测试创建的进程；超出优雅退出时间后进行有界强制回收。
     *
     * @param process 仅由本测试创建的子进程
     */
    protected void stop(Process process) throws InterruptedException {
        if (process != null && process.isAlive()) {
            process.destroy();
            if (!process.waitFor(12, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    /**
     * 通过真实 HTTP 客户端发送受控测试请求，不记录 Authorization。
     *
     * @param port 测试服务的临时端口
     * @param method 待匹配的 HTTP 方法
     * @param path 已限定到测试服务或公开合同的请求路径
     * @param token 待校验的原始凭证，只允许在当前调用内使用
     * @param body 当前接口字段白名单下的已校验正文
     * @param headers 受控测试附加请求头，不能包含开发环境真实凭证
     * @return 脱敏断言前保留的 HTTP 响应快照
     */
    protected Reply request(int port, String method, String path, String token, String body,
        Map<String, String> headers) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(20));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        headers.forEach(builder::header);
        builder.method(method,
            body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(response.statusCode(), response.body(), response.headers().map());
    }

    /**
     * 向测试 Gateway 发送请求，由真实 Core 执行认证和业务事务。
     *
     * @param method 待匹配的 HTTP 方法
     * @param path 已限定到测试服务或公开合同的请求路径
     * @param token 待校验的原始凭证，只允许在当前调用内使用
     * @param body 当前接口字段白名单下的已校验正文
     * @return 脱敏断言前保留的 HTTP 响应快照
     */
    protected Reply call(String method, String path, String token, String body) throws Exception {
        return request(gatewayPort, method, path, token, body, Map.of());
    }

    /**
     * 向测试 Gateway 发送请求，由真实 Core 执行认证和业务事务。
     *
     * @param method 待匹配的 HTTP 方法
     * @param path 已限定到测试服务或公开合同的请求路径
     * @param token 待校验的原始凭证，只允许在当前调用内使用
     * @param body 当前接口字段白名单下的已校验正文
     * @param idem 当前测试独占的业务幂等键
     * @return 脱敏断言前保留的 HTTP 响应快照
     */
    protected Reply call(String method, String path, String token, String body, String idem) throws Exception {
        return request(gatewayPort, method, path, token, body, Map.of("Idempotency-Key", idem));
    }

    /**
     * 断言 HTTP 状态和禁止缓存约束，失败响应展示前先脱敏。
     *
     * @param reply 待断言的测试响应
     * @param expected 当前场景期望的响应状态
     * @return 当前测试断言通过后的响应 JSON
     */
    protected JsonNode expect(Reply reply, int expected) {
        assertEquals(expected, reply.status(), () -> safe(reply.body()));
        if (reply.status() == 201 || reply.status() == 401 || reply.status() == 403) {
            assertTrue(reply.headers().getOrDefault("cache-control", List.of()).stream()
                .anyMatch(value -> value.contains("no-store")));
        }
        return reply.body().isBlank() ? null : json.readTree(reply.body());
    }

    /**
     * 将测试对象序列化为 HTTP 请求正文，仅用于隔离测试。
     *
     * @param object 待序列化的测试对象
     * @return 按字段白名单完成解析的请求正文
     */
    protected String body(Object object) {
        return json.writeValueAsString(object);
    }

    /**
     * 创建相互独立的首次初始化请求，避免唯一团队名偶然代替单例锁。
     *
     * @param team 团队安全视图
     * @param code 稳定错误码或员工编码
     * @return 当前操作生成或校验后的文本
     */
    protected String bootstrapBody(String team, String code) {
        return body(Map.of("teamName", team, "employeeCode", code, "displayName", "Administrator",
            "managementKeyExpiresAt", expiry()));
    }

    /**
     * 通过真实 HTTP 调用准备当前隔离测试的管理员身份。
     *
     * @return 仅当前测试使用的管理员身份
     */
    protected Session initialize() throws Exception {
        var result = expect(call("POST", "/api/v1/bootstrap/initialize", bootstrapToken,
            bootstrapBody("Platform", "ADM_001"), "initialize-0001"), 201);
        String token = result.get("managementCredential").get("key").asString();
        secretValues.add(token);
        return new Session(token, result.get("employee").get("id").asLong(), result.get("team").get("id").asLong());
    }

    /**
     * 读取指定团队；测试辅助方法通过真实 HTTP 创建场景所需团队。
     *
     * @param admin 当前测试首次初始化获得的管理员身份
     * @param name 受控名称；团队视图中表示团队名称
     * @return 按当前合同校验或计算后的数值
     */
    protected long team(Session admin, String name) throws Exception {
        return expect(call("POST", "/api/v1/teams", admin.key(), body(Map.of("name", name))), 201).get("id").asLong();
    }

    /**
     * 读取指定员工；测试辅助方法通过真实 HTTP 创建场景所需员工。
     *
     * @param admin 当前测试首次初始化获得的管理员身份
     * @param team 团队安全视图
     * @param code 稳定错误码或员工编码
     * @return 按当前合同校验或计算后的数值
     */
    protected long employee(Session admin, long team, String code) throws Exception {
        return expect(call("POST", "/api/v1/employees", admin.key(),
            body(Map.of("employeeCode", code, "displayName", code, "primaryTeamId", team))), 201).get("id").asLong();
    }

    /**
     * 构造合同允许的角色关系路径。
     *
     * @param employee 员工安全视图
     * @param role 目标角色编码
     * @param scope 目标作用域类型
     * @param scopeId 授权范围主键；GLOBAL 固定为 0
     * @return 当前操作生成或校验后的文本
     */
    protected String role(long employee, String role, String scope, long scopeId) {
        return "/api/v1/employees/" + employee + "/role-assignments/" + role + "/" + scope + "/" + scopeId;
    }

    /**
     * 构造指定员工的管理凭证路径。
     *
     * @param employee 员工安全视图
     * @return 当前操作生成或校验后的文本
     */
    protected String credentials(long employee) {
        return "/api/v1/employees/" + employee + "/management-credentials";
    }

    /**
     * 仅在当前测试内保存首次返回的密钥，后续断言不得再次回显。
     *
     * @param admin 当前测试首次初始化获得的管理员身份
     * @param employee 员工安全视图
     * @param idem 当前测试独占的业务幂等键
     * @param expiry 服务器校验的凭证到期时间
     * @return 当前测试断言通过后的响应 JSON
     */
    protected JsonNode issue(Session admin, long employee, String idem, String expiry) throws Exception {
        var reply = expect(call("POST", credentials(employee), admin.key(), body(Map.of("expiresAt", expiry)), idem),
            201);
        secretValues.add(reply.get("key").asString());
        return reply;
    }

    /**
     * 回收测试创建的进程和容器，并在保存日志前去除所有运行时秘密。
     */
    @AfterAll
    protected void cleanup() throws Exception {
        stop(gateway);
        stop(core);
        if (mysql != null) {
            mysql.stop();
        }
        if (Files.exists(outputDirectory)) {
            try (var files = Files.list(outputDirectory)) {
                for (Path path : files.filter(p -> p.getFileName().toString().endsWith(".log")).toList()) {
                    Files.writeString(path, safe(Files.readString(path)));
                }
            }
        }
        client.close();
    }
}
