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
     * @param status 当前接口允许的状态值
     * @param body 已校验的请求正文
     * @param headers 受控测试附加请求头，不能包含开发环境真实凭证
     * @author AIGate
     */
    protected record Reply(int status, String body, Map<String, List<String>> headers) {
    }

    /**
     * 测试管理员身份，密钥仅供当前测试进程使用，不写入版本控制或测试报告。
     *
     * @param key 当前字段名或操作幂等键，按方法用途传入
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
     * @return 无填充 Base64URL 编码的随机字符串
     */
    protected String random() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes());
    }

    /**
     * 生成可精确写入 DATETIME(6) 的测试凭证到期时间。
     *
     * @return 两天后到期、精度为微秒的 UTC 时间文本
     */
    protected String expiry() {
        // 生成两天后到期的时间，截断为 MySQL 可保存的微秒精度。
        return Instant.now().plus(Duration.ofDays(2)).truncatedTo(ChronoUnit.MICROS).toString();
    }

    /**
     * 规范化诊断标识或测试输出，防止秘密信息进入日志和报告。
     *
     * @param value 待检查或转换的值
     * @return 已经替换密钥内容的文本
     */
    protected String safe(String value) {
        // 替换已知测试密钥，再遮蔽其他带凭证前缀的文本。
        for (String secret : secretValues) {
            value = value.replace(secret, "[REDACTED]");
        }

        return value.replaceAll("(?:mgt_|bst_|svc_)[A-Za-z0-9_-]{20,}", "[REDACTED]");
    }

    /**
     * 向操作系统申请临时端口，避免接管开发者已经运行的服务。
     *
     * @return 操作系统分配的临时端口
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
     * @return 查询结果第一行第一列的整数
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
        // 1、检查 JDK 版本，创建测试输出目录并登记待脱敏的密钥。
        assertEquals(21, Runtime.version().feature(), "Use JDK21 to run the P1 contract");
        Files.createDirectories(outputDirectory);
        secretValues.addAll(List.of(rootPassword, appPassword, migratorPassword, serviceToken, bootstrapToken, pepper));

        // 2、启动独立 MySQL 容器，创建只读写数据的应用账号和可执行 DDL 的迁移账号。
        mysql = new MySQLContainer(DockerImageName.parse(MYSQL_IMAGE).asCompatibleSubstituteFor("mysql"))
            .withDatabaseName("core_db").withUsername("root").withPassword(rootPassword).withEnv("TZ", "UTC");
        mysql.start();

        sql("CREATE USER 'aigate_app'@'%' IDENTIFIED BY '" + appPassword + "'");
        sql("CREATE USER 'aigate_migrator'@'%' IDENTIFIED BY '" + migratorPassword + "'");
        sql("GRANT SELECT,INSERT,UPDATE,DELETE ON core_db.* TO 'aigate_app'@'%'");
        sql("GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,DROP,REFERENCES,INDEX,ALTER "
            + "ON core_db.* TO 'aigate_migrator'@'%'");

        // 3、创建额外测试库，用于检查应用账号无法跨库读取。
        sql("CREATE DATABASE finops_db");
        sql("CREATE TABLE finops_db.private_fact(id BIGINT)");

        // 4、申请临时端口，启动两个独立 JVM，并保存环境信息。
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
    // 库表由临时 MySQL 容器在运行时创建，仅忽略 IDE 的数据源和 SQL 名称解析提示。
    @SuppressWarnings({"SqlNoDataSourceInspection", "SqlResolve"})
    protected void emptyFixture() throws Exception {
        // 1、恢复上一场景修改的应用配置，未改动时继续复用 JVM。
        if (core == null || !core.isAlive() || !"INIT".equals(lastCoreMode) || !lastCoreOverrides.isEmpty()) {
            stop(core);
            startCore("INIT", Map.of());
        }

        if (gateway == null || !gateway.isAlive() || !coreUrl.equals(lastGatewayDestination)) {
            stop(gateway);
            startGateway(coreUrl);
        }

        // 2、在同一连接中关闭外键检查，清空临时表，再重建初始化控制行。
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
        // 1、指定临时库、端口及安全配置，不读取项目根目录的 .env。
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

        // 2、最后应用当前场景的覆盖配置。
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
        // 1、查找构建好的应用 JAR，使用当前 JDK 启动独立进程。
        Path jar = projectRoot.resolve(module + "/target/" + module + "-0.1.0-SNAPSHOT.jar");
        assertTrue(Files.exists(jar), "Build the full reactor before running integration tests");

        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar",
            jar.toString()).directory(projectRoot.toFile());

        // 2、删除继承的 AIGATE 和 MYSQL 配置，再注入当前测试环境。
        builder.environment().keySet().removeIf(key -> key.startsWith("AIGATE_") || key.startsWith("MYSQL_"));
        builder.environment().putAll(environment);

        // 3、合并标准输出和错误输出，写入本次测试日志。
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
        // 1、记录本次配置，启动 Core。
        lastCoreMode = mode;
        lastCoreOverrides = Map.copyOf(overrides);
        core = process("aigate-core", coreEnvironment(mode, overrides));

        // 2、携带服务凭证探测内部接口，收到 200 才继续测试。
        waitFor(core, corePort, "/internal/v1/ping", null,
            Map.of("X-AIGate-Service-Authorization", "Bearer " + serviceToken), 200);
    }

    /**
     * 启动测试 Gateway，只注入目标地址和服务凭证，不注入数据库秘密。
     *
     * @param destination 测试 Gateway 的 Core 地址
     */
    protected void startGateway(String destination) throws Exception {
        // 1、只向 Gateway 注入端口、Core 地址和服务凭证。
        lastGatewayDestination = destination;
        gateway = process("aigate-gateway", Map.of("AIGATE_GATEWAY_PORT", Integer.toString(gatewayPort),
            "AIGATE_CORE_URL", destination, "AIGATE_SERVICE_TOKEN", serviceToken));

        // 2、不带员工密钥访问 profile，预期 401 表示代理及 Core 已可响应。
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
        // 在限定次数内探测服务，进程退出或等待结束后仍未就绪则失败。
        for (int i = 0; i < 150; i++) {
            assertTrue(process.isAlive(), "Service exited during startup; see redacted target logs");
            try {
                if (request(port, "GET", path, token, null, headers).status() == expected) {
                    return;
                }
            } catch (IOException ignored) {
                // 启动时可能暂时连不上，继续探测；超过重试次数后测试会失败。
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
        // 先请求正常退出，超时后强制结束本测试创建的进程。
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
     * @param body 已校验的请求正文
     * @param headers 受控测试附加请求头，不能包含开发环境真实凭证
     * @return 脱敏断言前保留的 HTTP 响应快照
     */
    protected Reply request(int port, String method, String path, String token, String body,
        Map<String, String> headers) throws Exception {
        // 1、创建指向本机测试端口的 HTTP 请求。
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .timeout(Duration.ofSeconds(20));

        // 2、按当前场景添加凭证、正文和附加头，不输出原始密钥。
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }

        if (body != null) {
            builder.header("Content-Type", "application/json");
        }

        headers.forEach(builder::header);
        builder.method(method,
            body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));

        // 3、发送请求，保留状态码、正文和响应头供断言使用。
        var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());

        return new Reply(response.statusCode(), response.body(), response.headers().map());
    }

    /**
     * 向测试 Gateway 发送请求，由真实 Core 执行认证和业务事务。
     *
     * @param method 待匹配的 HTTP 方法
     * @param path 已限定到测试服务或公开合同的请求路径
     * @param token 待校验的原始凭证，只允许在当前调用内使用
     * @param body 已校验的请求正文
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
     * @param body 已校验的请求正文
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
        // 1、检查状态码，失败时先脱敏响应正文。
        assertEquals(expected, reply.status(), () -> safe(reply.body()));

        // 2、检查签发及认证失败响应包含 no-store。
        if (reply.status() == 201 || reply.status() == 401 || reply.status() == 403) {
            assertTrue(reply.headers().getOrDefault("cache-control", List.of()).stream()
                .anyMatch(value -> value.contains("no-store")));
        }

        // 3、有正文时解析 JSON，无正文时返回 null。
        return reply.body().isBlank() ? null : json.readTree(reply.body());
    }

    /**
     * 将测试对象序列化为 HTTP 请求正文，仅用于隔离测试。
     *
     * @param object 待序列化的测试对象
     * @return 序列化后的 JSON 字符串
     */
    protected String body(Object object) {
        return json.writeValueAsString(object);
    }

    /**
     * 创建相互独立的首次初始化请求，避免唯一团队名偶然代替单例锁。
     *
     * @param team 本次初始化使用的团队名称
     * @param code 稳定错误码或员工编码
     * @return 初始化接口使用的 JSON 请求正文
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
        // 1、通过真实初始化接口创建管理员，只读取首次响应中的密钥。
        var result = expect(call("POST", "/api/v1/bootstrap/initialize", bootstrapToken,
            bootstrapBody("Platform", "ADM_001"), "initialize-0001"), 201);
        String token = result.get("managementCredential").get("key").asString();

        // 2、立即将密钥加入脱敏列表，再返回当前测试身份。
        secretValues.add(token);

        return new Session(token, result.get("employee").get("id").asLong(), result.get("team").get("id").asLong());
    }

    /**
     * 通过真实创建接口准备测试团队并返回其主键。
     *
     * @param admin 当前测试首次初始化获得的管理员身份
     * @param name 本次操作使用的名称
     * @return 创建后的测试团队 ID
     */
    protected long team(Session admin, String name) throws Exception {
        return expect(call("POST", "/api/v1/teams", admin.key(), body(Map.of("name", name))), 201).get("id").asLong();
    }

    /**
     * 通过真实创建接口准备测试员工并返回其主键。
     *
     * @param admin 当前测试首次初始化获得的管理员身份
     * @param team 新员工归属的测试团队主键
     * @param code 稳定错误码或员工编码
     * @return 创建后的测试员工 ID
     */
    protected long employee(Session admin, long team, String code) throws Exception {
        return expect(call("POST", "/api/v1/employees", admin.key(),
            body(Map.of("employeeCode", code, "displayName", code, "primaryTeamId", team))), 201).get("id").asLong();
    }

    /**
     * 构造接口允许的角色关系路径。
     *
     * @param employee 目标测试员工主键
     * @param role 目标角色编码
     * @param scope 目标作用域类型
     * @param scopeId 授权范围主键；GLOBAL 固定为 0
     * @return 指定员工、角色及作用域的请求路径
     */
    protected String role(long employee, String role, String scope, long scopeId) {
        return "/api/v1/employees/" + employee + "/role-assignments/" + role + "/" + scope + "/" + scopeId;
    }

    /**
     * 构造指定员工的管理凭证路径。
     *
     * @param employee 目标测试员工主键
     * @return 指定员工的凭证请求路径
     */
    protected String credentials(long employee) {
        return "/api/v1/employees/" + employee + "/management-credentials";
    }

    /**
     * 仅在当前测试内保存首次返回的密钥，后续断言不得再次回显。
     *
     * @param admin 当前测试首次初始化获得的管理员身份
     * @param employee 目标测试员工主键
     * @param idem 当前测试独占的业务幂等键
     * @param expiry 服务器校验的凭证到期时间
     * @return 当前测试断言通过后的响应 JSON
     */
    protected JsonNode issue(Session admin, long employee, String idem, String expiry) throws Exception {
        // 1、通过真实签发接口创建密钥，要求返回 201。
        var reply = expect(call("POST", credentials(employee), admin.key(), body(Map.of("expiresAt", expiry)), idem),
            201);

        // 2、将新密钥加入脱敏列表，供后续断言和日志清理使用。
        secretValues.add(reply.get("key").asString());

        return reply;
    }

    /**
     * 回收测试创建的进程和容器，并在保存日志前去除所有运行时秘密。
     */
    @AfterAll
    protected void cleanup() throws Exception {
        // 1、停止本测试的 Gateway、Core 和 MySQL 容器。
        stop(gateway);
        stop(core);
        if (mysql != null) {
            mysql.stop();
        }

        // 2、逐个读取测试日志，脱敏后覆盖保存。
        if (Files.exists(outputDirectory)) {
            try (var files = Files.list(outputDirectory)) {
                for (Path path : files.filter(p -> p.getFileName().toString().endsWith(".log")).toList()) {
                    Files.writeString(path, safe(Files.readString(path)));
                }
            }
        }

        // 3、关闭 HTTP 客户端。
        client.close();
    }
}
