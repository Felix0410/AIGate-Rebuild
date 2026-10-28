package com.felix.aigatecore.finance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.felix.aigatecore.identity.RequestContext;
import com.felix.aigatecore.web.ApiException;
import com.felix.aigatecore.web.ValidatedBody;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.json.JsonMapper;

/** P4/P5真实MySQL行锁/事务及Core HTTP测试，所有数据只在临时容器；没有Provider客户端。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("finance-test")
@Import(FinanceMySqlTests.TimeConfiguration.class)
class FinanceMySqlTests {
    private static final String ROOT_PASSWORD = random();
    private static final String APP_PASSWORD = random();
    private static final String SERVICE = "svc_" + random();
    private static final String BOOTSTRAP = "bst_" + random();
    private static final MySQLContainer MYSQL = database();
    private final JsonMapper json = JsonMapper.builder().build();
    @Value("${local.server.port}")
    private int port;
    @Autowired
    private FakeFinanceFacade facade;
    @Autowired
    private FinanceManagementService management;
    @Autowired
    private GrantService grantService;
    @Autowired
    private com.felix.aigatecore.governance.GovernanceService governance;
    @Autowired
    private com.felix.aigatecore.governance.GovernanceEvaluator evaluator;
    @Autowired
    private org.springframework.transaction.PlatformTransactionManager tx;
    @Autowired
    private FinancePeriods periods;
    @Autowired
    private MutableClock clock;
    private RequestContext admin;
    private String adminKey;
    private com.sun.net.httpserver.HttpServer providerListener;
    private final AtomicInteger providerRequests = new AtomicInteger();

    private static String random() {
        byte[] value = new byte[32];
        new SecureRandom().nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static MySQLContainer database() {
        var mysql = new MySQLContainer(DockerImageName.parse("mysql@sha256:6ea90827b1100f8f2ae306a53"
            + "9f86d2c264a26ed435a2a9f75551dd5c3aeb242").asCompatibleSubstituteFor("mysql"))
            .withDatabaseName("core_db").withUsername("root").withPassword(ROOT_PASSWORD).withEnv("TZ", "UTC");
        mysql.start();
        try (var db = DriverManager.getConnection(mysql.getJdbcUrl(), "root", ROOT_PASSWORD);
            var sql = db.createStatement()) {
            sql.execute("CREATE USER 'finance_app'@'%' IDENTIFIED BY '" + APP_PASSWORD + "'");
            sql.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON core_db.* TO 'finance_app'@'%'");
        } catch (java.sql.SQLException failure) {
            mysql.stop();
            throw new IllegalStateException("Test database setup failed");
        }
        return mysql;
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("AIGATE_ENV_FILE", () -> "/tmp/aigate-no-user-env");
        registry.add("spring.datasource.url", () -> MYSQL.getJdbcUrl() + (MYSQL.getJdbcUrl().contains("?") ? "&" : "?")
            + "connectionTimeZone=UTC");
        registry.add("spring.datasource.username", () -> "finance_app");
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.flyway.user", () -> "root");
        registry.add("spring.flyway.password", () -> ROOT_PASSWORD);
        String governance = Base64.getEncoder().encodeToString(Base64.getUrlDecoder().decode(random()));
        registry.add("AIGATE_GOVERNANCE_AUDIT_HMAC_KEY_ID", () -> "gov-audit-v1");
        registry.add("AIGATE_GOVERNANCE_AUDIT_HMAC_KEY", () -> governance);
        registry.add("AIGATE_COMPANY_ZONE_ID", () -> "Asia/Shanghai");
        registry.add("AIGATE_SERVICE_TOKEN", () -> SERVICE);
        registry.add("AIGATE_BOOTSTRAP_MODE", () -> "INIT");
        registry.add("AIGATE_BOOTSTRAP_TOKEN", () -> BOOTSTRAP);
        registry.add("AIGATE_BOOTSTRAP_ALLOWED_CIDRS", () -> "127.0.0.1/32");
        registry.add("AIGATE_CATALOG_APPROVALS_FILE", () -> "");
        for (String purpose : List.of("MANAGEMENT", "AGENT", "EMPLOYEE", "PROVIDER_ISSUE")) {
            String key = Base64.getEncoder().encodeToString(Base64.getUrlDecoder().decode(random()));
            registry.add("AIGATE_" + purpose + "_PEPPER_KEY_ID", () -> purpose + "-v1");
            registry.add("AIGATE_" + purpose + "_PEPPER", () -> key);
        }
        String encryption = Base64.getEncoder().encodeToString(Base64.getUrlDecoder().decode(random()));
        registry.add("AIGATE_PROVIDER_ENCRYPTION_KEY_ID", () -> "enc-v1");
        registry.add("AIGATE_PROVIDER_ENCRYPTION_KEY", () -> encryption);
    }

    static class MutableClock extends Clock {
        private volatile Instant value = Instant.parse("2026-11-01T15:59:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return value;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfiguration {
        @Bean
        @Primary
        MutableClock financeClock() {
            return new MutableClock();
        }
    }

    private void sql(String text) throws Exception {
        try (var db = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", ROOT_PASSWORD);
            var statement = db.createStatement()) {
            statement.execute(text);
        }
    }

    private String scalar(String text) throws Exception {
        try (var db = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", ROOT_PASSWORD);
            var statement = db.createStatement();
            var result = statement.executeQuery(text)) {
            assertTrue(result.next());
            if (result.getMetaData().getColumnType(1) == java.sql.Types.DECIMAL) {
                return result.getBigDecimal(1).toPlainString();
            }
            return result.getString(1);
        }
    }

    private long count(String table) throws Exception {
        return Long.parseLong(scalar("SELECT COUNT(*) FROM " + table));
    }

    private HttpResponse<String> http(String method, String path, String key, String body) throws Exception {
        return http(method, path, key, body, "finance-fixture-bootstrap");
    }

    private HttpResponse<String> http(String method, String path, String key, String body, String idempotency)
        throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("X-AIGate-Service-Authorization", "Bearer " + SERVICE)
            .header("X-AIGate-Client-Address", "127.0.0.1")
            .header("Idempotency-Key", idempotency);
        if (key != null) {
            request.header("Authorization", "Bearer " + key);
        }
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        try (var client = HttpClient.newHttpClient()) {
            return client.send(request.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private ValidatedBody body(Map<String, Object> input, String... allowed) {
        return ValidatedBody.parse(json.writeValueAsBytes(input), allowed);
    }

    private void policy(String kind, long scope, String limit, Instant effective) {
        var policy = management.createPolicy(admin, body(Map.of("scopeType", kind, "scopeId", scope,
            "weeklyLimitPoints", limit, "effectiveFrom", effective.toString()),
            "scopeType", "scopeId", "weeklyLimitPoints", "effectiveFrom"));
        management.publishPolicy(admin, policy.id(), body(Map.of("expectedRevision", policy.revision()),
            "expectedRevision"));
    }

    private void budget(String currency, String month, String total, String normal, String emergency,
        Long version) {
        String text = "{\"limitAmount\":\"" + total + "\",\"normalLimitAmount\":\"" + normal
            + "\",\"emergencyLimitAmount\":\"" + emergency + "\",\"expectedVersion\":" + version + "}";
        management.putBudget(admin, currency, month,
            ValidatedBody.parseAllowingNull(text.getBytes(StandardCharsets.UTF_8),
                Set.of("expectedVersion"), "limitAmount", "normalLimitAmount", "emergencyLimitAmount",
                "expectedVersion"));
    }

    @BeforeEach
    void fixture() throws Exception {
        clock.value = Instant.parse("2026-11-01T15:59:00Z");
        try (var db = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", ROOT_PASSWORD);
            var statement = db.createStatement()) {
            var tables = new ArrayList<String>();
            try (var result = statement.executeQuery("SHOW TABLES")) {
                while (result.next()) {
                    if (!"flyway_schema_history".equals(result.getString(1))) {
                        tables.add(result.getString(1));
                    }
                }
            }
            statement.execute("SET FOREIGN_KEY_CHECKS=0");
            for (String table : tables) {
                statement.execute("TRUNCATE TABLE " + table);
            }
            statement.execute("SET FOREIGN_KEY_CHECKS=1");
        }
        sql("INSERT INTO bootstrap_control(id,version,created_at,updated_at) "
            + "VALUES(1,0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        var initialized = http("POST", "/api/v1/bootstrap/initialize", BOOTSTRAP,
            json.writeValueAsString(Map.of("teamName", "Finance Fixture", "employeeCode", "FINANCE_ADMIN",
                "displayName", "Test", "managementKeyExpiresAt", Instant.now().plusSeconds(60L * 86400).toString()
                    .replaceAll("\\.\\d+Z", "Z"))));
        assertEquals(201, initialized.statusCode());
        adminKey = json.readTree(initialized.body()).get("managementCredential").get("key").asString();
        admin = new RequestContext("Bearer " + adminKey, "127.0.0.1", "finance-test");
        // Direct inserts below are declared Fake fixtures, never production approval records.
        sql("INSERT INTO agent(application_code,display_name,owner_team_id,"
            + "created_by_employee_id,created_at,updated_at) "
            + "VALUES('FAKE_APP','Fake',1,1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        sql("INSERT INTO employee_agent_permission(employee_id,agent_id,granted_by_employee_id,granted_at,"
            + "created_at,updated_at) VALUES(1,1,1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        sql("INSERT INTO provider(code,name,hosting_mode,base_url_host_policy,created_at,updated_at) "
            + "VALUES('FAKE_PROVIDER','Fake','THIRD_PARTY_API','{}',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        sql("INSERT INTO logical_model(model_code,display_name,model_family,immutable_model_revision,"
            + "capability_profile_json,created_at,updated_at) VALUES('FAKE_MODEL','Fake','test','v1','{}',"
            + "UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        sql("INSERT INTO model_deployment(logical_model_id,provider_id,deployment_code,"
            + "endpoint_uri,remote_model_name,"
            + "hosting_mode,region_code,capability_revision,adapter_compatibility_json,adapter_evidence_ref,cost_model,"
            + "status,max_concurrent,created_at,updated_at) VALUES(1,1,'FAKE_DEPLOYMENT','https://fake.invalid','Fake',"
            + "'THIRD_PARTY_API','TEST','v1','{}','test-only','PER_TOKEN_API','ACTIVE',1,"
            + "UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        sql("INSERT INTO model_points_price_version(logical_model_id,revision,point_per_1k_input,point_per_1k_output,"
            + "status,effective_from,published_at,published_by,created_by,created_at) VALUES(1,1,10,15,'PUBLISHED',"
            + "'2026-01-01','2026-01-01',1,1,'2026-01-01')");
        sql("INSERT INTO deployment_purchase_price_version(deployment_id,revision,currency,amount_per_1k_input,"
            + "amount_per_1k_output,status,effective_from,published_at,published_by,created_by,created_at) "
            + "VALUES(1,1,'USD',0.015,0.015,'PUBLISHED','2026-01-01','2026-01-01',1,1,'2026-01-01')");
        sql("UPDATE logical_model SET active_points_price_version_id=1");
        sql("UPDATE model_deployment SET active_purchase_price_version_id=1");
        sql("INSERT INTO agent_model_permission(agent_id,logical_model_id,granted_by,granted_at,created_at,updated_at) "
            + "VALUES(1,1,1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        providerListener = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        providerListener.createContext("/", exchange -> {
            providerRequests.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        providerListener.start();
        sql("UPDATE model_deployment SET endpoint_uri='http://127.0.0.1:"
            + providerListener.getAddress().getPort() + "/v1/chat/completions'");
        policy("AGENT_WEEK", 1, "10.000000", Instant.parse("2026-01-01T00:00:00Z"));
        policy("EMPLOYEE_WEEK", 0, "20.000000", Instant.parse("2026-01-01T00:00:00Z"));
        budget("USD", "2026-11", "0.10000000", "0.08000000", "0.02000000", null);
    }

    private FakeFinanceFacade.Request request(String key) {
        return new FakeFinanceFacade.Request(key, 1, 1, 1, 1, 1000, 1000, true);
    }

    @Test
    @DisplayName("BIZP4-02/03/06：真实双账户25/0.03预留，12/0.015结算及安全幂等")
    void reservesAndSettlesFrozenAccountsExactlyOnce() throws Exception {
        var reserved = facade.reserve(request("normal-operation-001"));
        assertEquals("25.000000", reserved.pointsReserved());
        assertEquals("0.03000000", reserved.providerReserved());
        assertNull(reserved.pointsSettled());
        assertEquals("10.000000", scalar("SELECT reserved_points FROM quota_bucket WHERE bucket_kind='AGENT_WEEK'"));
        assertEquals("15.000000", scalar("SELECT reserved_points FROM quota_bucket WHERE bucket_kind='EMPLOYEE_WEEK'"));
        var settled = facade.settle("normal-operation-001", 600, 400);
        assertEquals("SETTLED", settled.status());
        assertEquals("12.000000", settled.pointsSettled());
        assertEquals("0.01500000", settled.providerSettled());
        assertEquals("10.000000", scalar("SELECT used_points FROM quota_bucket WHERE bucket_kind='AGENT_WEEK'"));
        assertEquals("2.000000", scalar("SELECT used_points FROM quota_bucket WHERE bucket_kind='EMPLOYEE_WEEK'"));
        assertEquals("13.000000", scalar("SELECT released_points FROM reservation_allocation WHERE priority_order=1"));
        assertEquals("0.00000000", scalar("SELECT reserved_amount FROM provider_spend_budget"));
        assertEquals(9, count("ledger_entry"));
        assertEquals(1, count("outbox_event"));
        assertEquals(settled, facade.settle("normal-operation-001", 600, 400));
        assertEquals("REPORT_EVIDENCE_CONFLICT", assertThrows(ApiException.class,
            () -> facade.settle("normal-operation-001", 601, 400)).code());
        assertThrows(ApiException.class, () -> facade.reserve(request("normal-operation-001")));
        assertEquals(9, count("ledger_entry"));
        assertEquals(1, count("outbox_event"));
        assertTrue(scalar("SELECT payload_json FROM outbox_event").contains("testOnly"));
        assertEquals("FINANCE_OPERATION_TEST_SETTLED", scalar("SELECT event_type FROM outbox_event"));
    }

    private List<Boolean> competing(java.util.concurrent.Callable<Boolean> action) throws Exception {
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var task = (java.util.concurrent.Callable<Boolean>) () -> {
                ready.countDown();
                go.await();
                return action.call();
            };
            var first = workers.submit(task);
            var second = workers.submit(task);
            ready.await();
            go.countDown();
            return List.of(first.get(), second.get());
        }
    }

    @Test
    @DisplayName("BIZP4-04/06：MySQL并发不同操作不能超发，相同操作键不能重复扣账")
    void concurrentReservationsCannotOverspendOrDuplicate() throws Exception {
        var sequence = new java.util.concurrent.atomic.AtomicInteger();
        var results = competing(() -> {
            try {
                facade.reserve(request("concurrent-operation-" + sequence.incrementAndGet()));
                return true;
            } catch (ApiException failure) {
                assertEquals("INSUFFICIENT_POINTS", failure.code());
                return false;
            }
        });
        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        assertEquals(1, count("finance_operation"));
        assertEquals("25.000000", scalar("SELECT SUM(reserved_points) FROM quota_bucket"));
        assertEquals("0.03000000", scalar("SELECT reserved_amount FROM provider_spend_budget"));
        assertEquals("0", scalar("SELECT COUNT(*) FROM quota_bucket WHERE used_points+reserved_points>limit_points"));
        var operation = scalar("SELECT operation_key FROM finance_operation");
        var settlements = competing(() -> {
            assertEquals("SETTLED", facade.settle(operation, 600, 400).status());
            return true;
        });
        assertEquals(2, settlements.stream().filter(Boolean::booleanValue).count());
        assertEquals(1, count("outbox_event"));
    }

    @Test
    @DisplayName("BIZP4-06：同operationKey并发只有一条永久收据，不同请求也不能重签")
    void concurrentSameOperationKeyHasOnePermanentReservation() throws Exception {
        var sequence = new java.util.concurrent.atomic.AtomicInteger();
        var results = competing(() -> {
            try {
                facade.reserve(new FakeFinanceFacade.Request("same-key-concurrency", 1, 1, 1, 1,
                    900 + sequence.incrementAndGet(), 1000, true));
                return true;
            } catch (ApiException failure) {
                assertEquals("OPERATION_ALREADY_EXISTS", failure.code());
                return false;
            }
        });
        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        assertEquals(1, count("finance_operation"));
        assertEquals(1, count("points_reservation"));
        assertEquals(1, count("provider_spend_reservation"));
        assertEquals(3, count("ledger_entry"));
    }

    @Test
    @DisplayName("BIZP4-05：采购不足和积分不足都回滚Operation、两账、Allocation、Ledger")
    void eitherInsufficientAccountRollsBackEverything() throws Exception {
        budget("USD", "2026-11", "0.02000000", "0.01000000", "0.01000000", 0L);
        assertEquals("INSUFFICIENT_PROVIDER_BUDGET", assertThrows(ApiException.class,
            () -> facade.reserve(request("insufficient-provider"))).code());
        assertEquals(0, count("finance_operation"));
        assertEquals(0, count("quota_bucket"));
        budget("USD", "2026-11", "0.10000000", "0.08000000", "0.02000000", 1L);
        policy("EMPLOYEE_WEEK", 0, "0.000000", Instant.parse("2026-02-01T00:00:00Z"));
        assertEquals("INSUFFICIENT_POINTS", assertThrows(ApiException.class,
            () -> facade.reserve(request("insufficient-points"))).code());
        for (String table : List.of("finance_operation", "quota_bucket", "points_reservation",
            "provider_spend_reservation", "reservation_allocation", "ledger_entry", "outbox_event")) {
            assertEquals(0, count(table));
        }
        assertEquals("0.00000000", scalar("SELECT reserved_amount FROM provider_spend_budget"));
    }

    @Test
    @DisplayName("BIZP4-07/D50-04：SELF_HOSTED只记POINTS，不生成零美元采购")
    void selfHostedHasNoInventedCurrency() throws Exception {
        sql("UPDATE model_deployment SET hosting_mode='SELF_HOSTED',cost_model='INTERNAL_RESOURCE',"
            + "active_purchase_price_version_id=NULL");
        sql("UPDATE provider SET hosting_mode='SELF_HOSTED'");
        var result = facade.reserve(request("self-hosted-operation"));
        assertNull(result.currency());
        assertNull(result.maxCurrencyAmount());
        assertNull(result.providerReserved());
        facade.settle("self-hosted-operation", 600, 400);
        assertEquals(0, count("provider_spend_reservation"));
        assertEquals("0", scalar("SELECT COUNT(*) FROM ledger_entry WHERE unit='CURRENCY'"));
    }

    @Test
    @DisplayName("BIZP4-08/D50-01：跨周、转Team和策略发布不改写原周/原团队/冻结价格")
    void settlementKeepsOriginalWeekTeamAndPrice() throws Exception {
        facade.reserve(request("old-week-operation"));
        String oldWeek = scalar("SELECT employee_week_start FROM finance_operation");
        policy("AGENT_WEEK", 1, "99.000000", Instant.parse("2026-02-01T00:00:00Z"));
        clock.value = Instant.parse("2026-11-02T01:00:00Z");
        sql("INSERT INTO team(name,created_at,updated_at) VALUES('New Team',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        sql("UPDATE employee SET primary_team_id=2 WHERE id=1");
        sql("UPDATE logical_model SET active_points_price_version_id=NULL");
        facade.settle("old-week-operation", 600, 400);
        assertEquals(oldWeek, scalar("SELECT employee_week_start FROM finance_operation"));
        assertEquals("1", scalar("SELECT employee_team_snapshot_id FROM finance_operation"));
        assertEquals("10.000000", scalar("SELECT limit_points FROM quota_bucket WHERE bucket_kind='AGENT_WEEK'"));
        assertTrue(management.quotas(admin, 1, Map.of()).agentWeekly().isEmpty());
        assertNull(management.quotas(admin, 1, Map.of()).employeeWeekly());
        assertEquals("12.000000", management.operation(admin, "old-week-operation").pointsSettled());
    }

    @Test
    @DisplayName("BIZP4-08：月末预留次月结算仍属于原币种原月预算")
    void settlementKeepsOriginalMonthAndCurrency() throws Exception {
        clock.value = Instant.parse("2026-10-31T15:59:00Z");
        budget("USD", "2026-10", "0.10000000", "0.08000000", "0.02000000", null);
        facade.reserve(request("old-month-operation"));
        clock.value = Instant.parse("2026-11-01T01:00:00Z");
        facade.settle("old-month-operation", 600, 400);
        assertEquals("0.01500000", management.getBudget(admin, "USD", "2026-10").settledAmount());
        assertEquals("0.00000000", management.getBudget(admin, "USD", "2026-11").settledAmount());
        budget("EUR", "2026-11", "1.00000000", "1.00000000", "0.00000000", null);
        assertEquals(3, management.listBudgets(admin, Map.of()).total());
    }

    @Test
    @DisplayName("BIZP4-09/10：缺策略、缺预算、不可证明类别和错误价拒绝且Provider HTTP为零")
    void missingPrerequisitesAndUnverifiableBillingNeverReserve() throws Exception {
        assertEquals(422, assertThrows(ApiException.class, () -> facade.reserve(
            new FakeFinanceFacade.Request("unknown-billing-category", 1, 1, 1, 1, 1000, 1000, false))).status());
        sql("UPDATE quota_policy SET status='DRAFT'");
        assertThrows(ApiException.class, () -> facade.reserve(request("missing-policy-operation")));
        sql("UPDATE quota_policy SET status='ACTIVE'");
        sql("DELETE FROM provider_spend_partition");
        sql("DELETE FROM provider_spend_budget");
        assertThrows(ApiException.class, () -> facade.reserve(request("missing-budget-operation")));
        sql("UPDATE logical_model SET active_points_price_version_id=NULL");
        assertThrows(ApiException.class, () -> facade.reserve(request("missing-price-operation")));
        assertEquals(0, count("finance_operation"));
        assertEquals(0, count("ledger_entry"));
    }

    @Test
    @DisplayName("BIZP4-11/D50-04：UNKNOWN保留原双预留，费用为null且不能释放风险")
    void unknownUsageRetainsReservationsWithoutInventingZero() throws Exception {
        facade.reserve(request("unknown-usage-operation"));
        var pending = facade.unknown("unknown-usage-operation");
        assertEquals("PENDING_RECONCILIATION", pending.status());
        assertNull(pending.pointsSettled());
        assertNull(pending.providerSettled());
        assertFalse(pending.usageEvidencePresent());
        assertEquals("25.000000", pending.pointsReserved());
        assertEquals("0.03000000", scalar("SELECT reserved_amount FROM provider_spend_budget"));
        assertEquals(0, count("outbox_event"));
        assertThrows(ApiException.class, () -> facade.releaseProvenNotSent("unknown-usage-operation"));
        assertEquals(pending, facade.unknown("unknown-usage-operation"));
        assertEquals("SETTLED", facade.settle("unknown-usage-operation", 600, 400).status());
    }

    @Test
    @DisplayName("BIZP4-12/D50-03：OVERRUN记录真实采购并冻结，预算不能下调到已占用以下")
    void overrunRecordsExposureAndFreezesCurrencyWithoutBreakingPointsCheck() throws Exception {
        facade.reserve(request("overrun-operation"));
        assertThrows(ApiException.class,
            () -> budget("USD", "2026-11", "0.02000000", "0.02000000", "0.00000000", 1L));
        var settled = facade.settle("overrun-operation", 5000, 5000);
        assertEquals("OVERRUN", settled.status());
        assertEquals("0.15000000", settled.providerSettled());
        assertEquals("FROZEN", scalar("SELECT risk_state FROM provider_spend_budget"));
        assertEquals("100.000000", scalar("SELECT overrun_points FROM finance_operation"));
        assertEquals("0.12000000", scalar("SELECT overrun_currency_amount FROM finance_operation"));
        assertEquals("0", scalar("SELECT COUNT(*) FROM quota_bucket WHERE used_points+reserved_points>limit_points"));
        budget("USD", "2026-11", "1.00000000", "0.98000000", "0.02000000", 2L);
        assertEquals("FROZEN", scalar("SELECT risk_state FROM provider_spend_budget"));
        assertEquals("PROVIDER_BUDGET_UNAVAILABLE", assertThrows(ApiException.class,
            () -> facade.reserve(request("frozen-budget-operation"))).code());
    }

    @Test
    @DisplayName("BIZP4-05：真实Outbox写入故障使结算两账、Ledger和证据全部回滚")
    void outboxFailureRollsBackSettlementAndRetryIsSafe() throws Exception {
        facade.reserve(request("outbox-failure-operation"));
        sql("CREATE TRIGGER reject_finance_outbox BEFORE INSERT ON outbox_event FOR EACH ROW "
            + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test failure'");
        try {
            assertThrows(RuntimeException.class, () -> facade.settle("outbox-failure-operation", 600, 400));
            assertEquals("RESERVED", scalar("SELECT status FROM finance_operation"));
            assertEquals("25.000000", scalar("SELECT SUM(reserved_points) FROM quota_bucket"));
            assertEquals("0.03000000", scalar("SELECT reserved_amount FROM provider_spend_budget"));
            assertEquals(3, count("ledger_entry"));
            assertEquals(0, count("outbox_event"));
        } finally {
            sql("DROP TRIGGER reject_finance_outbox");
        }
        assertEquals("SETTLED", facade.settle("outbox-failure-operation", 600, 400).status());
        assertEquals(1, count("outbox_event"));
    }

    @Test
    @DisplayName("P4资金释放：只有可信发送前证据才能释放两账，重放不产生重复Ledger")
    void provenNotSentReleasesBothAccountsOnce() throws Exception {
        facade.reserve(request("not-sent-operation"));
        assertEquals("RELEASED", facade.releaseProvenNotSent("not-sent-operation").status());
        assertEquals("0.000000", scalar("SELECT SUM(reserved_points) FROM quota_bucket"));
        assertEquals("0.00000000", scalar("SELECT reserved_amount FROM provider_spend_budget"));
        facade.releaseProvenNotSent("not-sent-operation");
        assertEquals(9, count("ledger_entry"));
        assertEquals(0, count("outbox_event"));
    }

    @Test
    @DisplayName("D50-02：真实HTTP固定刻度、null哨兵、CAS及未知字段严格拒绝")
    void httpContractRejectsAmbiguousAmountsAndVersions() throws Exception {
        String path = "/api/v1/provider-budgets/USD/2026-12";
        String valid = "{\"limitAmount\":\"0.10000000\",\"normalLimitAmount\":\"0.08000000\","
            + "\"emergencyLimitAmount\":\"0.02000000\",\"expectedVersion\":null}";
        for (String bad : List.of(valid.replace("\"0.10000000\"", "0.1"),
            valid.replace("0.10000000", "1e-1"), valid.replace("0.10000000", "0.1"),
            valid.replace("0.10000000", "-0.10000000"), valid.replace("0.10000000", "9999999999999.00000000"),
            valid.replace("null", "\"0\""), valid.replace("null", "-1"),
            valid.replace("\"0.10000000\"", "null"), valid.replace("\"normalLimitAmount\"", "\"arbitrary\""))) {
            assertTrue(http("PUT", path, adminKey, bad).statusCode() >= 400);
        }
        assertEquals(201, http("PUT", path, adminKey, valid).statusCode());
        assertEquals(409, http("PUT", path, adminKey, valid).statusCode());
        assertEquals(200, http("PUT", path, adminKey, valid.replace("null", "0")).statusCode());
        assertEquals(409, http("PUT", path, adminKey, valid.replace("null", "0")).statusCode());
        assertEquals(400, http("PUT", "/api/v1/provider-budgets/usd/2026-12", adminKey, valid).statusCode());
        assertEquals(400, http("PUT", "/api/v1/provider-budgets/USD/2026-2", adminKey, valid).statusCode());
        assertEquals(404, http("GET", "/api/v1/provider-budgets/USD/2027-01", adminKey, null).statusCode());
        assertEquals(401, http("GET", path, null, null).statusCode());
        assertEquals(405, http("POST", "/api/v1/finance-operations/anything", adminKey, "{}").statusCode());
    }

    @Test
    @DisplayName("D50-01：并发发布同一草稿只能一次成功，重复旧revision返回409")
    void concurrentPolicyPublishHasOneWinner() throws Exception {
        var draft = management.createPolicy(admin, body(Map.of("scopeType", "AGENT_WEEK", "scopeId", 1,
            "weeklyLimitPoints", "11.000000", "effectiveFrom", "2026-02-01T00:00:00Z"),
            "scopeType", "scopeId", "weeklyLimitPoints", "effectiveFrom"));
        var results = competing(() -> {
            try {
                management.publishPolicy(admin, draft.id(), body(Map.of("expectedRevision", draft.revision()),
                    "expectedRevision"));
                return true;
            } catch (ApiException failure) {
                assertEquals(409, failure.status());
                return false;
            }
        });
        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        assertEquals("1",
            scalar("SELECT COUNT(*) FROM quota_policy WHERE scope_type='AGENT_WEEK' AND status='ACTIVE'"));
    }

    @Test
    @DisplayName("P4精度与周期：CEILING风险上界/HALF_UP结算，DST周和月转UTC")
    void decimalRoundingAndIanaPeriodsAreExplicit() {
        assertEquals(new BigDecimal("0.000001"), FinanceAmounts.charge(1, 0,
            new BigDecimal("0.000001"), BigDecimal.ZERO, 6, RoundingMode.CEILING));
        assertEquals(new BigDecimal("0.000000"), FinanceAmounts.charge(1, 0,
            new BigDecimal("0.000001"), BigDecimal.ZERO, 6, RoundingMode.HALF_UP));
        assertEquals(Instant.parse("2026-10-25T16:00:00Z"), periods.week(clock.instant()).start());
        var environment = new org.springframework.mock.env.MockEnvironment()
            .withProperty("AIGATE_COMPANY_ZONE_ID", "America/New_York");
        var dst = new FinancePeriods(environment);
        var week = dst.week(Instant.parse("2026-03-08T12:00:00Z"));
        assertEquals(167, java.time.Duration.between(week.start(), week.end()).toHours());
        assertEquals(Instant.parse("2026-03-01T05:00:00Z"), dst.month("2026-03").start());
        assertThrows(IllegalStateException.class, () -> new FinancePeriods(
            new org.springframework.mock.env.MockEnvironment().withProperty("AIGATE_COMPANY_ZONE_ID", "bad-zone")));
    }

    @Test
    @DisplayName("BIZP4-01/FZSQL-10：V4十表与规范一致，真实CHECK/FK/唯一键及应用DDL隔离")
    void schemaMatchesDesignAndDatabaseConstraintsRemainEnforced() throws Exception {
        var root = java.nio.file.Path.of("..");
        String design = java.nio.file.Files.readString(root.resolve("docs/database/core-v04.md"));
        String ddl = design.split("```sql\n", 2)[1].split("```", 2)[0].lines()
            .filter(line -> !line.stripLeading().startsWith("--"))
            .collect(java.util.stream.Collectors.joining("\n")).strip();
        assertEquals(ddl, java.nio.file.Files.readString(root.resolve(
            "aigate-core/src/main/resources/db/migration/V4__normal_quota_billing.sql")).strip());
        assertEquals(7, count("flyway_schema_history"));
        for (String table : List.of("quota_policy", "quota_bucket", "provider_spend_budget",
            "provider_spend_partition", "finance_operation", "points_reservation", "reservation_allocation",
            "provider_spend_reservation", "ledger_entry", "outbox_event")) {
            assertEquals("1", scalar("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='core_db'"
                + " AND table_name='" + table + "'"));
        }
        assertThrows(java.sql.SQLException.class, () -> sql("UPDATE quota_policy SET weekly_limit_points=-1"));
        assertThrows(java.sql.SQLException.class, () -> sql("UPDATE provider_spend_partition SET budget_id=99999"));
        assertThrows(java.sql.SQLException.class, () -> sql("UPDATE provider_spend_budget SET currency='usd'"));
        facade.reserve(request("constraint-operation"));
        assertThrows(java.sql.SQLException.class,
            () -> sql("UPDATE quota_bucket SET reserved_points=limit_points+1"));
        assertThrows(java.sql.SQLException.class,
            () -> sql("UPDATE finance_operation SET status='INVALID'"));
        assertThrows(java.sql.SQLException.class,
            () -> sql("UPDATE reservation_allocation SET settled_points=reserved_points+1"));
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "finance_app", APP_PASSWORD);
            var statement = connection.createStatement()) {
            assertThrows(java.sql.SQLException.class, () -> statement.execute("CREATE TABLE forbidden_p4(id INT)"));
        }
    }

    private GrantService.PoolResult teamPool(String amount, Long version) {
        String text = "{\"limitPoints\":\"" + amount + "\",\"periodEnd\":\"2026-12-01\",\"expectedVersion\":"
            + version + "}";
        return grantService.putPool(admin, 1, "2026-11-01", ValidatedBody.parseAllowingNull(
            text.getBytes(StandardCharsets.UTF_8), Set.of("expectedVersion"), "limitPoints", "periodEnd",
            "expectedVersion"));
    }

    private com.aigate.contracts.dto.GrantDTO grant(String key, String amount, String expires) {
        return grantService.issue(admin, 1, body(Map.of("recipientEmployeeId", 1, "points", amount,
            "expiresAt", expires, "reasonCode", "TEAM_ALLOCATION"),
            "recipientEmployeeId", "points", "expiresAt", "reasonCode"), key);
    }

    private void zeroWeeklyPolicies() {
        policy("AGENT_WEEK", 1, "0.000000", Instant.parse("2026-02-01T00:00:00Z"));
        policy("EMPLOYEE_WEEK", 0, "0.000000", Instant.parse("2026-02-01T00:00:00Z"));
    }

    @Test
    @DisplayName("BIZP5-01：V5三表与规范相符，真实FK/CHECK/收据唯一及V4账表仍可用")
    void grantSchemaMatchesDesignAndPreservesDatabaseConstraints() throws Exception {
        var root = java.nio.file.Path.of("..");
        String design = java.nio.file.Files.readString(root.resolve("docs/database/core-v05.md"));
        String ddl = design.split("```sql\n", 2)[1].split("```", 2)[0].lines()
            .filter(line -> !line.stripLeading().startsWith("--"))
            .collect(java.util.stream.Collectors.joining("\n")).strip();
        assertEquals(ddl, java.nio.file.Files.readString(root.resolve(
            "aigate-core/src/main/resources/db/migration/V5__team_grant.sql")).strip());
        teamPool("1000.000000", null);
        var grant = grant("schema-grant-001", "100.000000", "2026-11-05T00:00:00Z");
        assertThrows(java.sql.SQLException.class, () -> sql("UPDATE team_pool SET issued_points=limit_points+1"));
        assertThrows(java.sql.SQLException.class, () -> sql("UPDATE team_grant SET recipient_employee_id=99999"));
        assertThrows(java.sql.SQLException.class, () -> sql("UPDATE team_grant SET used_points=issued_points+1"));
        assertThrows(java.sql.SQLException.class, () -> sql("UPDATE team_grant SET status='REVOKED_FOR_NEW_USE'"));
        assertThrows(java.sql.SQLException.class, () -> sql("UPDATE team_grant_issue_receipt SET amount_points=0"));
        assertEquals(1, count("team_grant_issue_receipt"));
        assertEquals(grant.grantId(), Long.parseLong(scalar("SELECT team_grant_id FROM team_grant_issue_receipt")));
        assertEquals(7, count("flyway_schema_history"));
    }

    @Test
    @DisplayName("BIZP5-02/04/D50-05：发行扣池一次，永久收据、Ledger与完整规范Outbox原子写入")
    void grantIssueIsOneAtomicFactAndReplayNeverIssuesAgain() throws Exception {
        teamPool("1000.000000", null);
        var granted = grant("permanent-issue-key", "100.000000", "2026-11-05T00:00:00Z");
        assertEquals("100.000000", scalar("SELECT issued_points FROM team_pool"));
        assertEquals("900.000000", grantService.pool(admin, 1, Map.of("periodStart", "2026-11-01")).availablePoints());
        assertEquals("TEAM_ALLOCATION", scalar("SELECT issued_reason_code FROM team_grant"));
        assertEquals(1, count("team_grant_issue_receipt"));
        assertEquals(1, count("ledger_entry"));
        assertEquals(1, count("outbox_event"));
        var replay = assertThrows(ApiException.class,
            () -> grant("permanent-issue-key", "100.000000", "2026-11-05T00:00:00Z"));
        assertEquals("GRANT_ALREADY_ISSUED", replay.code());
        assertTrue(replay.getMessage().contains("grantId=" + granted.grantId()));
        assertEquals("IDEMPOTENCY_CONFLICT", assertThrows(ApiException.class,
            () -> grant("permanent-issue-key", "101.000000", "2026-11-05T00:00:00Z")).code());
        assertEquals(1, count("team_grant"));
        assertEquals(1, count("outbox_event"));
        var payload = json.readTree(scalar("SELECT payload_json FROM outbox_event"));
        assertEquals("TEAM_GRANT_ISSUED_POINTS", payload.get("metricType").asString());
        assertEquals("100.000000", payload.get("pointsAmount").asString());
        assertEquals(1, payload.get("issuerTeamId").asLong());
        assertEquals(1, payload.get("recipientEmployeeId").asLong());
        assertTrue(payload.get("currencyAmount").isNull());
        assertTrue(payload.get("usageTeamSnapshotId").isNull());
        var canonical = new java.util.TreeMap<String, tools.jackson.databind.JsonNode>();
        payload.properties().forEach(entry -> canonical.put(entry.getKey(), entry.getValue()));
        byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(json.writeValueAsBytes(canonical));
        assertEquals(java.util.HexFormat.of().formatHex(hash), scalar("SELECT payload_digest FROM outbox_event"));
        assertEquals("GRANT:" + granted.grantId() + ":ISSUED:1", scalar("SELECT business_event_key FROM outbox_event"));
    }

    @Test
    @DisplayName("BIZP5-05/FZSQL-15：并发发行700+600不能透支1000，唯一操作键并发也只发一次")
    void concurrentGrantIssuesCannotExceedPoolOrDuplicateReceipt() throws Exception {
        teamPool("1000.000000", null);
        AtomicInteger sequence = new AtomicInteger();
        var results = competing(() -> {
            int n = sequence.incrementAndGet();
            try {
                grant("parallel-issue-" + n, n == 1 ? "700.000000" : "600.000000", "2026-11-05T00:00:00Z");
                return true;
            } catch (ApiException failure) {
                assertEquals("INSUFFICIENT_TEAM_POOL", failure.code());
                return false;
            }
        });
        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        assertEquals("0", scalar("SELECT COUNT(*) FROM team_pool WHERE issued_points>limit_points"));
        assertEquals(1, count("team_grant"));
        var duplicate = competing(() -> {
            try {
                grant("same-grant-issue-key", "1.000000", "2026-11-05T00:00:00Z");
                return true;
            } catch (ApiException failure) {
                assertEquals("GRANT_ALREADY_ISSUED", failure.code());
                return false;
            }
        });
        assertEquals(1, duplicate.stream().filter(Boolean::booleanValue).count());
        assertEquals(2, count("team_grant_issue_receipt"));
    }

    @Test
    @DisplayName("BIZP5-06：40POINTS按Agent10、Employee5、Grant25分配，Grant消费不再次扣池")
    void reservationUsesWeeklyThenGrantWithoutChargingPoolTwice() throws Exception {
        teamPool("1000.000000", null);
        grant("allocation-grant-key", "100.000000", "2026-11-05T00:00:00Z");
        policy("EMPLOYEE_WEEK", 0, "5.000000", Instant.parse("2026-02-01T00:00:00Z"));
        facade.reserve(new FakeFinanceFacade.Request("grant-allocation-operation", 1, 1, 1, 1, 1000, 2000, true));
        assertEquals("10.000000",
            scalar("SELECT reserved_points FROM reservation_allocation WHERE source_type='AGENT_WEEK'"));
        assertEquals("5.000000",
            scalar("SELECT reserved_points FROM reservation_allocation WHERE source_type='EMPLOYEE_WEEK'"));
        assertEquals("25.000000",
            scalar("SELECT reserved_points FROM reservation_allocation WHERE source_type='GRANT'"));
        facade.settle("grant-allocation-operation", 0, 2000);
        assertEquals("15.000000", scalar("SELECT used_points FROM team_grant"));
        assertEquals("0.000000", scalar("SELECT reserved_points FROM team_grant"));
        assertEquals("100.000000", scalar("SELECT issued_points FROM team_pool"));
        assertEquals("1", scalar("SELECT COUNT(*) FROM outbox_event WHERE event_type='TEAM_GRANT_ISSUED_V1'"));
    }

    @Test
    @DisplayName("BIZP5-07：Grant严格FEFO，同到期时id小者先用；不受主键锁序影响")
    void grantConsumptionUsesFefoAndStableIdTieBreak() throws Exception {
        teamPool("1000.000000", null);
        zeroWeeklyPolicies();
        var later = grant("later-expiring-grant", "10.000000", "2026-11-05T00:00:00Z");
        var first = grant("first-expiring-grant", "10.000000", "2026-11-04T00:00:00Z");
        var tie = grant("tie-expiring-grant", "10.000000", "2026-11-04T00:00:00Z");
        facade.reserve(request("fefo-operation-key"));
        assertEquals(Long.toString(first.grantId()), scalar("SELECT source_id FROM reservation_allocation "
            + "WHERE source_type='GRANT' ORDER BY priority_order LIMIT 1"));
        assertEquals("5.000000", scalar("SELECT reserved_points FROM team_grant WHERE id=" + later.grantId()));
        assertEquals("10.000000", scalar("SELECT reserved_points FROM team_grant WHERE id=" + tie.grantId()));
        facade.settle("fefo-operation-key", 0, 1000);
        assertEquals("10.000000", scalar("SELECT used_points FROM team_grant WHERE id=" + first.grantId()));
        assertEquals("5.000000", scalar("SELECT used_points FROM team_grant WHERE id=" + tie.grantId()));
        assertEquals("0.000000", scalar("SELECT used_points FROM team_grant WHERE id=" + later.grantId()));
    }

    @Test
    @DisplayName("BIZP5-08/09/10/D50-06：预留后到期仍结算原Grant，剩余到期不退款或新用")
    void expiredGrantSettlesOldReservationButNeverFundsNewUseOrRefundsPool() throws Exception {
        teamPool("1000.000000", null);
        zeroWeeklyPolicies();
        grant("expiring-reserved-grant", "100.000000", "2026-11-02T00:00:00Z");
        facade.reserve(new FakeFinanceFacade.Request("expired-reservation-key", 1, 1, 1, 1, 1000, 2000, true));
        clock.value = Instant.parse("2026-11-02T01:00:00Z");
        facade.settle("expired-reservation-key", 0, 2000);
        assertEquals("30.000000", scalar("SELECT used_points FROM team_grant"));
        assertEquals("100.000000", scalar("SELECT issued_points FROM team_pool"));
        var expired = grantService.received(admin, Map.of("status", "EXPIRED"));
        assertEquals(1, expired.total());
        assertEquals("0.000000", expired.items().getFirst().availablePoints());
        assertEquals(0, grantService.received(admin, Map.of("status", "ACTIVE")).total());
        assertEquals("INSUFFICIENT_POINTS", assertThrows(ApiException.class,
            () -> facade.reserve(request("expired-new-operation"))).code());
        var repeat = assertThrows(ApiException.class,
            () -> grant("expiring-reserved-grant", "100.000000", "2026-11-02T00:00:00Z"));
        assertEquals("GRANT_ALREADY_ISSUED", repeat.code());
    }

    @Test
    @DisplayName("BIZP5-11/R-AUD-13：UNKNOWN时撤销只禁新用，保留旧账且重复撤销不覆盖理由")
    void revokePreservesPendingExposureAndOriginalAuditReason() throws Exception {
        teamPool("1000.000000", null);
        zeroWeeklyPolicies();
        var grant = grant("revoked-grant-key", "100.000000", "2026-11-05T00:00:00Z");
        facade.reserve(request("pending-grant-operation"));
        facade.unknown("pending-grant-operation");
        grantService.revoke(admin, 1, grant.grantId(), body(Map.of("reasonCode", "ACCESS_REMOVED"), "reasonCode"));
        assertEquals("25.000000", scalar("SELECT reserved_points FROM team_grant"));
        assertEquals("PENDING_RECONCILIATION", scalar("SELECT status FROM finance_operation"));
        long audits = count("admin_audit_event");
        grantService.revoke(admin, 1, grant.grantId(), body(Map.of("reasonCode", "REPEATED_REQUEST"), "reasonCode"));
        assertEquals(audits, count("admin_audit_event"));
        assertEquals("ACCESS_REMOVED", scalar("SELECT revoke_reason_code FROM team_grant"));
        assertEquals("1", scalar("SELECT revoked_by FROM team_grant"));
        assertEquals("0.000000", grantService.received(admin, Map.of()).items().getFirst().availablePoints());
        assertThrows(ApiException.class, () -> facade.reserve(request("revoked-new-operation")));
        facade.settle("pending-grant-operation", 600, 400);
        assertEquals("12.000000", scalar("SELECT used_points FROM team_grant"));
        assertEquals("100.000000", scalar("SELECT issued_points FROM team_pool"));
    }

    @Test
    @DisplayName("P5并发消费：两operation争用30Grant只允许一份25预留，池发行额不变")
    void concurrentGrantConsumersCannotDoubleReserve() throws Exception {
        teamPool("1000.000000", null);
        zeroWeeklyPolicies();
        grant("shared-small-grant", "30.000000", "2026-11-05T00:00:00Z");
        AtomicInteger sequence = new AtomicInteger();
        var results = competing(() -> {
            try {
                facade.reserve(request("shared-grant-operation-" + sequence.incrementAndGet()));
                return true;
            } catch (ApiException failure) {
                assertEquals("INSUFFICIENT_POINTS", failure.code());
                return false;
            }
        });
        assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
        assertEquals("25.000000", scalar("SELECT reserved_points FROM team_grant"));
        assertEquals("30.000000", scalar("SELECT issued_points FROM team_pool"));
        assertEquals(1, count("finance_operation"));
    }

    @Test
    @DisplayName("P5故障注入：发行Outbox失败，Pool/Grant/Receipt/Ledger/Audit全部回滚")
    void grantIssueOutboxFailureRollsBackPoolReceiptAndLedger() throws Exception {
        teamPool("1000.000000", null);
        long audits = count("admin_audit_event");
        sql("CREATE TRIGGER reject_grant_outbox BEFORE INSERT ON outbox_event FOR EACH ROW "
            + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test failure'");
        try {
            assertThrows(RuntimeException.class,
                () -> grant("outbox-rollback-grant", "100.000000", "2026-11-05T00:00:00Z"));
            assertEquals("0.000000", scalar("SELECT issued_points FROM team_pool"));
            assertEquals(0, count("team_grant"));
            assertEquals(0, count("team_grant_issue_receipt"));
            assertEquals(0, count("ledger_entry"));
            assertEquals(audits, count("admin_audit_event"));
        } finally {
            sql("DROP TRIGGER reject_grant_outbox");
        }
        grant("outbox-rollback-grant", "100.000000", "2026-11-05T00:00:00Z");
        assertEquals(1, count("team_grant_issue_receipt"));
    }

    @Test
    @DisplayName("P5池CAS与发行窗口：旧版本、重叠周期、限额低于已发行及窗口外发行均拒绝")
    void poolCasPeriodAndLoweringCannotHideAlreadyIssuedMoney() throws Exception {
        teamPool("1000.000000", null);
        grant("pool-limit-grant", "100.000000", "2026-11-05T00:00:00Z");
        assertEquals("INVALID_STATE", assertThrows(ApiException.class, () -> teamPool("99.000000", 1L)).code());
        assertEquals("VERSION_CONFLICT", assertThrows(ApiException.class, () -> teamPool("1000.000000", 0L)).code());
        String overlap = "{\"limitPoints\":\"10.000000\",\"periodEnd\":\"2026-12-15\",\"expectedVersion\":null}";
        assertEquals("POOL_PERIOD_OVERLAP", assertThrows(ApiException.class, () -> grantService.putPool(admin, 1,
            "2026-11-15", ValidatedBody.parseAllowingNull(overlap.getBytes(StandardCharsets.UTF_8),
                Set.of("expectedVersion"), "limitPoints", "periodEnd", "expectedVersion")))
            .code());
        clock.value = Instant.parse("2026-12-01T00:00:00Z");
        assertEquals("TEAM_POOL_UNAVAILABLE", assertThrows(ApiException.class,
            () -> grant("closed-pool-grant", "1.000000", "2026-12-05T00:00:00Z")).code());
        assertEquals("100.000000", scalar("SELECT issued_points FROM team_pool"));
    }

    @Test
    @DisplayName("P5发送前释放：已到期Grant只释放原预留，不增加可用或退回TeamPool")
    void releaseAfterGrantExpiryCannotRefundIssuerOrReviveGrant() throws Exception {
        teamPool("1000.000000", null);
        zeroWeeklyPolicies();
        grant("expired-release-grant", "30.000000", "2026-11-02T00:00:00Z");
        facade.reserve(request("expired-release-operation"));
        clock.value = Instant.parse("2026-11-02T01:00:00Z");
        facade.releaseProvenNotSent("expired-release-operation");
        assertEquals("0.000000", scalar("SELECT reserved_points FROM team_grant"));
        assertEquals("30.000000", scalar("SELECT issued_points FROM team_pool"));
        assertEquals("0.000000", grantService.received(admin, Map.of()).items().getFirst().availablePoints());
    }

    private Map<String, String> tableDigests(String schema, List<String> tables) throws Exception {
        var result = new java.util.TreeMap<String, String>();
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", ROOT_PASSWORD);
            var statement = connection.createStatement()) {
            for (String table : tables) {
                var hash = java.security.MessageDigest.getInstance("SHA-256");
                try (var rows = statement.executeQuery("SELECT * FROM " + schema + "." + table + " ORDER BY id")) {
                    while (rows.next()) {
                        for (int column = 1; column <= rows.getMetaData().getColumnCount(); column++) {
                            byte[] bytes = rows.getBytes(column);
                            hash.update((byte) (bytes == null ? 0 : 1));
                            if (bytes != null) {
                                hash.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                                hash.update(bytes);
                            }
                        }
                    }
                }
                result.put(table, java.util.HexFormat.of().formatHex(hash.digest()));
            }
        }
        return result;
    }

    private Map<String, Integer> history(String schema) throws Exception {
        var result = new java.util.TreeMap<String, Integer>();
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", ROOT_PASSWORD);
            var statement = connection.createStatement();
            var rows = statement.executeQuery("SELECT version,checksum FROM " + schema + ".flyway_schema_history")) {
            while (rows.next()) {
                result.put(rows.getString(1), rows.getInt(2));
            }
        }
        return result;
    }

    @Test
    @DisplayName("BIZP5-01：真实V4旧账升级V5，27张原表逐列内容和历史checksum保持，重复迁移无变化")
    void realV4HistoryWithSettledAndPendingMoneySurvivesV5() throws Exception {
        facade.reserve(request("v4-settled-history"));
        facade.settle("v4-settled-history", 600, 400);
        facade.reserve(new FakeFinanceFacade.Request("v4-pending-history", 1, 1, 1, 1, 100, 100, true));
        facade.unknown("v4-pending-history");
        sql("CREATE DATABASE grant_upgrade_db");
        String url = MYSQL.getJdbcUrl().replace("/core_db", "/grant_upgrade_db");
        org.flywaydb.core.Flyway.configure().dataSource(url, "root", ROOT_PASSWORD)
            .locations("classpath:db/migration").target("4").load().migrate();
        var tables = new ArrayList<String>();
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", ROOT_PASSWORD);
            var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema='grant_upgrade_db' AND table_name!='flyway_schema_history' "
                + "ORDER BY table_name")) {
                while (rows.next()) {
                    tables.add(rows.getString(1));
                }
            }
            statement.execute("SET FOREIGN_KEY_CHECKS=0");
            for (String table : tables) {
                statement.execute("TRUNCATE TABLE grant_upgrade_db." + table);
                statement.execute("INSERT INTO grant_upgrade_db." + table + " SELECT * FROM core_db." + table);
            }
            statement.execute("SET FOREIGN_KEY_CHECKS=1");
        }
        assertEquals(27, tables.size());
        var previous = tableDigests("grant_upgrade_db", tables);
        var checksums = history("grant_upgrade_db");
        assertEquals(4, checksums.size());
        var flyway = org.flywaydb.core.Flyway.configure().dataSource(url, "root", ROOT_PASSWORD)
            .locations("classpath:db/migration").target("5").load();
        assertEquals(1, flyway.migrate().migrationsExecuted);
        assertEquals(previous, tableDigests("grant_upgrade_db", tables));
        var upgraded = history("grant_upgrade_db");
        assertEquals(5, upgraded.size());
        checksums.forEach((version, checksum) -> assertEquals(checksum, upgraded.get(version)));
        assertEquals(0, flyway.migrate().migrationsExecuted);
        assertEquals(previous, tableDigests("grant_upgrade_db", tables));
        assertEquals(upgraded, history("grant_upgrade_db"));
    }

    @org.junit.jupiter.api.Nested
    @DisplayName("P6真实MySQL治理状态机、当前策略锁与安全判定")
    class GovernanceIntegration {
        private record Actors(RequestContext editor, RequestContext approver, long editorId, long approverId) {
        }

        private Actors actors() throws Exception {
            var editor = actor("GOV_EDITOR", "POLICY_EDITOR");
            var approver = actor("GOV_APPROVER", "SECURITY_APPROVER");
            return new Actors(editor.getValue(), approver.getValue(), editor.getKey(), approver.getKey());
        }

        private Map.Entry<Long, RequestContext> actor(String code, String role) throws Exception {
            var created = http("POST", "/api/v1/employees", adminKey, json.writeValueAsString(Map.of(
                "employeeCode", code, "displayName", code, "primaryTeamId", 1)));
            assertEquals(201, created.statusCode());
            long employee = json.readTree(created.body()).get("id").asLong();
            assertEquals(201, http("PUT", "/api/v1/employees/" + employee + "/role-assignments/" + role + "/GLOBAL/0",
                adminKey, null).statusCode());
            var credential = http("POST", "/api/v1/employees/" + employee + "/management-credentials", adminKey,
                json.writeValueAsString(Map.of("expiresAt", Instant.now().plusSeconds(60L * 86400)
                    .truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString())),
                "gov-credential-" + employee);
            assertEquals(201, credential.statusCode());
            String key = json.readTree(credential.body()).get("key").asString();
            return Map.entry(employee, new RequestContext("Bearer " + key, "127.0.0.1", "gov-mgt-" + employee));
        }

        private tools.jackson.databind.node.ObjectNode policyJson(Long base) throws Exception {
            var node = (tools.jackson.databind.node.ObjectNode) json.readTree(java.nio.file.Files.readString(
                java.nio.file.Path.of("../config/test-fixtures/governance-policy.json")));
            if (base == null) {
                node.putNull("baseRevision");
            } else {
                node.put("baseRevision", base);
            }
            return node;
        }

        private ValidatedBody draftBody(tools.jackson.databind.node.ObjectNode node) {
            return ValidatedBody.parseAllowingNull(json.writeValueAsBytes(node), Set.of("baseRevision"), "baseRevision",
                "classificationRules", "egressMatrix", "outputReviewRules", "reason");
        }

        private long approvedDraft(Actors actors, Long base, boolean restrictive) throws Exception {
            var config = policyJson(base);
            if (restrictive) {
                for (var cell : config.get("egressMatrix")) {
                    if ("INTERNAL".equals(cell.get("dataClass").asString())
                        && "THIRD_PARTY_API".equals(cell.get("hostingMode").asString())) {
                        ((tools.jackson.databind.node.ObjectNode) cell).put("decision", "DENY");
                    }
                }
            }
            long revision = governance.draft(actors.editor(), draftBody(config)).revisionId();
            governance.submit(actors.editor(), revision);
            governance.review(actors.approver(), revision, body(Map.of("decision", "APPROVE", "reasonCode", "REVIEW_OK",
                "expectedVersion", 1), "decision", "reasonCode", "expectedVersion"));
            return revision;
        }

        private ValidatedBody publishBody(long version, Long active) {
            String value = "{\"expectedPolicyVersion\":" + version + ",\"expectedActiveRevisionId\":" + active + "}";
            return ValidatedBody.parseAllowingNull(value.getBytes(StandardCharsets.UTF_8),
                Set.of("expectedActiveRevisionId"),
                "expectedPolicyVersion", "expectedActiveRevisionId");
        }

        private long published(Actors actors) throws Exception {
            long revision = approvedDraft(actors, null, false);
            governance.publish(actors.approver(), revision, publishBody(0, null));
            return revision;
        }

        private void egress(Actors actors) {
            governance.approveEgress(actors.approver(), body(Map.of("agentId", 1, "providerId", 1, "regionCode", "TEST",
                "approvedClassMax", "INTERNAL", "validFrom", "2026-01-01T00:00:00Z", "validUntil",
                clock.instant().plusSeconds(7200).toString(), "reasonCode", "EGRESS_REVIEW_OK"),
                "agentId", "providerId", "regionCode", "approvedClassMax", "validFrom", "validUntil", "reasonCode"));
        }

        private void boundary(Actors actors, String classification, Long version, boolean approved) {
            String value = "{\"approvedClassMax\":\"" + classification + "\",\"regionCode\":\"TEST\",\"validUntil\":\""
                + clock.instant().plusSeconds(7200) + "\",\"dataProcessingApproved\":" + approved
                + ",\"expectedVersion\":" + version + ",\"reasonCode\":\"BOUNDARY_REVIEW_OK\"}";
            governance.boundary(actors.approver(), 1,
                ValidatedBody.parseAllowingNull(value.getBytes(StandardCharsets.UTF_8),
                    Set.of("expectedVersion"), "approvedClassMax", "regionCode", "validUntil", "dataProcessingApproved",
                    "expectedVersion", "reasonCode"));
        }

        private com.felix.aigatecore.governance.GovernanceEvaluator.Context context(String phase, String floor) {
            return new com.felix.aigatecore.governance.GovernanceEvaluator.Context(1, 1, 1, 1, floor,
                "gov-" + random(), phase, 1);
        }

        private byte[] messages(String content, boolean stream) {
            return json.writeValueAsBytes(Map.of("messages", List.of(Map.of("role", "user", "content", content)),
                "stream", stream));
        }

        private void localDeployment() throws Exception {
            sql("UPDATE model_deployment SET hosting_mode='SELF_HOSTED',cost_model='INTERNAL_RESOURCE',"
                + "active_purchase_price_version_id=NULL");
            sql("UPDATE provider SET hosting_mode='SELF_HOSTED'");
        }

        private long exception(Actors actors) {
            long id = governance.requestException(admin,
                body(Map.of("agentId", 1, "modelCode", "FAKE_MODEL", "ruleCode",
                    "APPROVED_SENSITIVE_MARKERS_V1", "reasonCode", "FALSE_POSITIVE_REVIEW", "validUntil",
                    clock.instant().plusSeconds(3600).toString()), "agentId", "modelCode", "ruleCode", "reasonCode",
                    "validUntil"),
                "gov-exception-" + random()).requestId();
            governance.reviewException(actors.approver(), id,
                body(Map.of("decision", "APPROVE", "reasonCode", "REVIEW_OK",
                    "expectedVersion", 0), "decision", "reasonCode", "expectedVersion"));
            return id;
        }

        @Test
        @DisplayName("BIZP6-01/02/D50-07：无策略fail closed，Admin不隐含安全角色，自审即使双角色也拒绝")
        void defaultDenyAndSeparationOfDutiesAreRealDatabaseFacts() throws Exception {
            var denied = evaluator.evaluate(context("PREQUEUE", null), messages("ordinary", false));
            assertEquals("POLICY_NOT_CONFIGURED", denied.reasonCode());
            assertEquals("DENY", denied.decision());
            assertEquals(0, count("governance_decision_audit"));
            Actors actors = actors();
            assertThrows(ApiException.class, () -> governance.draft(admin, draftBody(policyJson(null))));
            var draft = governance.draft(actors.editor(), draftBody(policyJson(null)));
            assertEquals("NOT_CONFIGURED", governance.active(admin).status());
            governance.submit(actors.editor(), draft.revisionId());
            assertEquals(201, http("PUT", "/api/v1/employees/" + actors.editorId()
                + "/role-assignments/SECURITY_APPROVER/GLOBAL/0", adminKey, null).statusCode());
            assertEquals(403,
                assertThrows(ApiException.class, () -> governance.review(actors.editor(), draft.revisionId(),
                    body(Map.of("decision", "APPROVE", "reasonCode", "SELF_REVIEW", "expectedVersion", 1),
                        "decision", "reasonCode", "expectedVersion")))
                    .status());
            assertEquals("UNDER_REVIEW", scalar("SELECT status FROM governance_policy_revision"));
            assertEquals("0", scalar("SELECT COUNT(*) FROM governance_policy_revision WHERE approved_by IS NOT NULL"));
        }

        @Test
        @DisplayName("BIZP6-03/R-AUD-01：真实并发Review和双CAS发布只成功一次，旧版内容不可变")
        void reviewAndPublishCompareAndSwapHaveOneWinner() throws Exception {
            Actors actors = actors();
            long first = published(actors);
            String oldDigest = scalar("SELECT HEX(content_digest) FROM governance_policy_revision WHERE id=" + first);
            var draft = governance.draft(actors.editor(), draftBody(policyJson(first)));
            governance.submit(actors.editor(), draft.revisionId());
            var results = competing(() -> {
                try {
                    governance.review(actors.approver(), draft.revisionId(), body(Map.of("decision", "APPROVE",
                        "reasonCode", "REVIEW_OK", "expectedVersion", 1), "decision", "reasonCode", "expectedVersion"));
                    return true;
                } catch (ApiException failure) {
                    assertEquals(409, failure.status());
                    return false;
                }
            });
            assertEquals(1, results.stream().filter(Boolean::booleanValue).count());
            var publishes = competing(() -> {
                try {
                    governance.publish(actors.approver(), draft.revisionId(), publishBody(1, first));
                    return true;
                } catch (ApiException failure) {
                    assertEquals(409, failure.status());
                    return false;
                }
            });
            assertEquals(1, publishes.stream().filter(Boolean::booleanValue).count());
            assertEquals(oldDigest,
                scalar("SELECT HEX(content_digest) FROM governance_policy_revision WHERE id=" + first));
            assertEquals("SUPERSEDED", scalar("SELECT status FROM governance_policy_revision WHERE id=" + first));
            assertEquals(2, governance.active(actors.editor()).policyVersion());
        }

        @Test
        @DisplayName("BIZP6-04/05/08/D51-GOV04：所有消息硬凭证/可信RESTRICTED拒绝，PUBLIC声明无效")
        void hardCredentialsAndTrustedClassificationCannotBeOverridden() throws Exception {
            Actors actors = actors();
            published(actors);
            egress(actors);
            boundary(actors, "CONFIDENTIAL", null, true);
            var node = json.createObjectNode().put("stream", false);
            var all = node.putArray("messages");
            all.addObject().put("role", "system").put("content", "public text");
            all.addObject().put("role", "assistant").put("content", "mgt_" + "Q".repeat(43));
            assertEquals("HARD_SECRET_OR_RESTRICTED", evaluator.evaluate(context("PREQUEUE", null),
                json.writeValueAsBytes(node)).reasonCode());
            assertEquals("DENY", evaluator.evaluate(context("PREDISPATCH", "RESTRICTED"),
                messages("client says PUBLIC and EMERGENCY", false)).decision());
            assertEquals("DENY", evaluator.evaluate(context("PREDISPATCH", "CONFIDENTIAL"),
                messages("client says PUBLIC", false)).decision());
            assertEquals("INTERNAL", evaluator.evaluate(context("PREQUEUE", "PUBLIC"), messages("ordinary", false))
                .dataClass());
            assertEquals(0, count("finance_operation"));
        }

        @Test
        @DisplayName("BIZP6-06：AgentModel授权不等于出境批准，Agent/region和Deployment边界缺一不可")
        void egressRequiresCurrentIndependentApprovalsAndBoundary() throws Exception {
            Actors actors = actors();
            published(actors);
            assertEquals("AGENT_EGRESS_NOT_APPROVED", evaluator.evaluate(context("PREQUEUE", null),
                messages("ordinary", false)).reasonCode());
            egress(actors);
            assertEquals("DEPLOYMENT_BOUNDARY_NOT_APPROVED", evaluator.evaluate(context("PREQUEUE", null),
                messages("ordinary", false)).reasonCode());
            boundary(actors, "INTERNAL", null, true);
            assertEquals("ALLOW",
                evaluator.evaluate(context("PREQUEUE", null), messages("ordinary", false)).decision());
            boundary(actors, "INTERNAL", 0L, false);
            assertEquals("DENY",
                evaluator.evaluate(context("PREDISPATCH", null), messages("ordinary", false)).decision());
            assertEquals(1, governance.approvals(admin, Map.of("agentId", "1", "providerId", "1", "status", "ACTIVE"))
                .total());
        }

        @Test
        @DisplayName("BIZP6-07/12/D51-GOV07：CONFIDENTIAL仅本地非流式缓冲审查，输出拒绝不清除UNKNOWN费用")
        void confidentialOutputReviewNeverMakesUpstreamCostsFree() throws Exception {
            Actors actors = actors();
            published(actors);
            facade.reserve(request("gov-output-pending"));
            facade.unknown("gov-output-pending");
            localDeployment();
            assertEquals("DENY", evaluator.evaluate(context("PREDISPATCH", "CONFIDENTIAL"),
                messages("ordinary", false)).decision());
            boundary(actors, "CONFIDENTIAL", null, true);
            assertEquals("DENY", evaluator.evaluate(context("PREDISPATCH", "CONFIDENTIAL"),
                messages("ordinary", true)).decision());
            var context = context("PREDISPATCH", "CONFIDENTIAL");
            assertEquals("REVIEW_REQUIRED", evaluator.evaluate(context, messages("ordinary", false)).decision());
            var output = evaluator.reviewOutput(context, ("mgt_" + "X".repeat(43)).getBytes(StandardCharsets.UTF_8));
            assertEquals("DENY", output.decision());
            assertEquals("OUTPUT_SECRET_DENIED", output.reasonCode());
            assertEquals("PENDING_RECONCILIATION", scalar("SELECT status FROM finance_operation"));
            assertEquals("25.000000", scalar("SELECT SUM(reserved_points) FROM quota_bucket"));
            assertEquals("0.03000000", scalar("SELECT reserved_amount FROM provider_spend_budget"));
            var safe = context("PREDISPATCH", "CONFIDENTIAL");
            evaluator.evaluate(safe, messages("ordinary", false));
            assertEquals("ALLOW",
                evaluator.reviewOutput(safe, "safe response".getBytes(StandardCharsets.UTF_8)).decision());
            var huge = context("PREDISPATCH", "CONFIDENTIAL");
            evaluator.evaluate(huge, messages("ordinary", false));
            assertEquals("OUTPUT_LIMIT_EXCEEDED", evaluator.reviewOutput(huge, new byte[262145]).reasonCode());
        }

        @Test
        @DisplayName("BIZP6-09/D51-GOV05/R-AUD-02：误报例外绑定主体和修订，不能豁免来源/硬凭证/其它标记")
        void exceptionCannotLowerTrustedFloorOrSuppressOtherSignals() throws Exception {
            Actors actors = actors();
            published(actors);
            egress(actors);
            boundary(actors, "CONFIDENTIAL", null, true);
            assertEquals("DENY", evaluator.evaluate(context("PREQUEUE", null),
                messages("TEST-CONFIDENTIAL-MARKER", false)).decision());
            long exception = exception(actors);
            assertEquals("ALLOW", evaluator.evaluate(context("PREQUEUE", null),
                messages("TEST-CONFIDENTIAL-MARKER", false)).decision());
            Instant beforeExpiry = clock.instant();
            clock.value = beforeExpiry.plusSeconds(3601);
            assertEquals("DENY", evaluator.evaluate(context("PREDISPATCH", null),
                messages("TEST-CONFIDENTIAL-MARKER", false)).decision());
            clock.value = beforeExpiry;
            sql("INSERT INTO employee_agent_permission(employee_id,agent_id,granted_by_employee_id,granted_at,"
                + "created_at,updated_at) VALUES(" + actors.editorId()
                + ",1,1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
            var otherEmployee = new com.felix.aigatecore.governance.GovernanceEvaluator.Context(actors.editorId(),
                1, 1, 1, null, "gov-other-" + random(), "PREDISPATCH", 1);
            assertEquals("DENY", evaluator.evaluate(otherEmployee,
                messages("TEST-CONFIDENTIAL-MARKER", false)).decision());
            assertEquals("DENY", evaluator.evaluate(context("PREQUEUE", "CONFIDENTIAL"),
                messages("TEST-CONFIDENTIAL-MARKER", false)).decision());
            assertEquals("DENY", evaluator.evaluate(context("PREQUEUE", null),
                messages("TEST-CONFIDENTIAL-MARKER TEST-RESTRICTED-MARKER", false)).decision());
            assertEquals("DENY", evaluator.evaluate(context("PREQUEUE", null),
                messages("TEST-CONFIDENTIAL-MARKER mgt_" + "Q".repeat(43), false)).decision());
            governance.revokeException(actors.approver(), exception, body(Map.of("reasonCode", "REVOKED_APPROVAL",
                "expectedVersion", 1), "reasonCode", "expectedVersion"));
            assertThrows(ApiException.class, () -> governance.revokeException(actors.approver(), exception,
                body(Map.of("reasonCode", "REPEAT_REVOKE", "expectedVersion", 1), "reasonCode", "expectedVersion")));
            assertEquals("DENY", evaluator.evaluate(context("PREDISPATCH", null),
                messages("TEST-CONFIDENTIAL-MARKER", false)).decision());
        }

        @Test
        @DisplayName("BIZP6-10：PREQUEUE旧策略允许，发布后PREDISPATCH重扫；扫描期间变更拒绝陈旧结果")
        void freshAndStaleScansCannotBypassPublishedPolicy() throws Exception {
            Actors actors = actors();
            long first = published(actors);
            egress(actors);
            boundary(actors, "INTERNAL", null, true);
            assertEquals("ALLOW",
                evaluator.evaluate(context("PREQUEUE", null), messages("ordinary", false)).decision());
            var prepared = evaluator.prepare(context("PREDISPATCH", null), messages("ordinary", false));
            long second = approvedDraft(actors, first, true);
            governance.publish(actors.approver(), second, publishBody(1, first));
            assertEquals("POLICY_REVISION_CHANGED", evaluator.finalizePrepared(prepared).reasonCode());
            assertEquals("EGRESS_MATRIX_DENY", evaluator.evaluate(context("PREDISPATCH", null),
                messages("ordinary", false)).reasonCode());
            assertEquals(0, count("finance_operation"));
        }

        @Test
        @DisplayName("BIZP6-11：最终治理共享锁和发布独占锁真实竞争，先提交顺序决定策略归属")
        void finalGuardAndPolicyPublishHaveDatabaseLinearization() throws Exception {
            Actors actors = actors();
            long first = published(actors);
            egress(actors);
            boundary(actors, "INTERNAL", null, true);
            long second = approvedDraft(actors, first, true);
            var prepared = evaluator.prepare(context("PREDISPATCH", null), messages("ordinary", false));
            var acquired = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            try (var workers = Executors.newFixedThreadPool(2)) {
                var guard = workers.submit(() -> new org.springframework.transaction.support.TransactionTemplate(tx)
                    .execute(status -> {
                        var verdict = evaluator.finalizePrepared(prepared);
                        acquired.countDown();
                        try {
                            assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
                        } catch (InterruptedException failure) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("Test interrupted");
                        }
                        return verdict;
                    }));
                assertTrue(acquired.await(5, java.util.concurrent.TimeUnit.SECONDS));
                var published = workers
                    .submit(() -> governance.publish(actors.approver(), second, publishBody(1, first)));
                try {
                    assertThrows(java.util.concurrent.TimeoutException.class,
                        () -> published.get(150, java.util.concurrent.TimeUnit.MILLISECONDS));
                } finally {
                    release.countDown();
                }
                assertEquals("ALLOW", guard.get().decision());
                assertEquals(second, published.get().revisionId());
            }
            assertEquals("DENY",
                evaluator.evaluate(context("PREDISPATCH", null), messages("ordinary", false)).decision());
        }

        @Test
        @DisplayName("BIZP6-12：损坏规则/UTF8/超限输入均拒绝，审计没有正文/标记/HMAC回读")
        void corruptedPolicyAndBadInputsFailClosedWithoutPersistingText() throws Exception {
            Actors actors = actors();
            published(actors);
            egress(actors);
            boundary(actors, "INTERNAL", null, true);
            String prompt = "private synthetic text mgt_" + "X".repeat(43);
            evaluator.evaluate(context("PREQUEUE", null), messages(prompt, false));
            var invalid = evaluator.evaluate(context("PREQUEUE", null), new byte[]{(byte) 0xff});
            assertEquals("GOVERNANCE_SCANNER_UNAVAILABLE", invalid.reasonCode());
            var tooLarge = evaluator.evaluate(context("PREQUEUE", null), new byte[65537]);
            assertEquals("DENY", tooLarge.decision());
            var decisions = governance.decisions(admin, Map.of("agentId", "1", "decision", "DENY"));
            assertEquals(3, decisions.total());
            String exposed = json.writeValueAsString(decisions);
            assertFalse(exposed.contains("private synthetic"));
            assertFalse(exposed.contains("inputHmac"));
            String stored = scalar(
                "SELECT CAST(matched_rule_codes_json AS CHAR) FROM governance_decision_audit LIMIT 1");
            assertFalse(stored.contains(prompt));
            assertFalse(stored.contains("TEST-CONFIDENTIAL-MARKER"));
            sql("UPDATE governance_rule SET enabled=FALSE WHERE rule_code='CREDENTIAL_PREFIX_HARD_DENY_V1'");
            assertEquals("POLICY_INTEGRITY_FAILURE", evaluator.evaluate(context("PREDISPATCH", null),
                messages("ordinary", false)).reasonCode());
        }

        @Test
        @DisplayName("P6真实故障注入：治理审计落库失败不可返回ALLOW，未创建资金或Provider许可")
        void auditFailureCannotReturnAllow() throws Exception {
            Actors actors = actors();
            published(actors);
            egress(actors);
            boundary(actors, "INTERNAL", null, true);
            sql("CREATE TRIGGER reject_governance_decision BEFORE INSERT ON governance_decision_audit FOR EACH ROW "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test failure'");
            try {
                assertThrows(RuntimeException.class,
                    () -> evaluator.evaluate(context("PREDISPATCH", null), messages("ordinary", false)));
                assertEquals(0, count("governance_decision_audit"));
                assertEquals(0, count("finance_operation"));
            } finally {
                sql("DROP TRIGGER reject_governance_decision");
            }
        }

        @Test
        @DisplayName("BIZP6-01/FZSQL-11/16：真实V5历史升级V6，30张原表数据/checksum保留，新角色受限")
        void realV5HistorySurvivesV6RoleCheckReplacement() throws Exception {
            teamPool("1000.000000", null);
            grant("gov-upgrade-grant", "100.000000", "2026-11-05T00:00:00Z");
            sql("CREATE DATABASE governance_upgrade_db");
            String url = MYSQL.getJdbcUrl().replace("/core_db", "/governance_upgrade_db");
            org.flywaydb.core.Flyway.configure().dataSource(url, "root", ROOT_PASSWORD)
                .locations("classpath:db/migration").target("5").load().migrate();
            var tables = new ArrayList<String>();
            try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", ROOT_PASSWORD);
                var statement = connection.createStatement()) {
                try (var rows = statement.executeQuery("SELECT table_name FROM information_schema.tables "
                    + "WHERE table_schema='governance_upgrade_db' AND table_name!='flyway_schema_history'")) {
                    while (rows.next()) {
                        tables.add(rows.getString(1));
                    }
                }
                statement.execute("SET FOREIGN_KEY_CHECKS=0");
                for (String table : tables) {
                    statement.execute("TRUNCATE TABLE governance_upgrade_db." + table);
                    statement.execute("INSERT INTO governance_upgrade_db." + table + " SELECT * FROM core_db." + table);
                }
                statement.execute("SET FOREIGN_KEY_CHECKS=1");
            }
            assertEquals(30, tables.size());
            var before = tableDigests("governance_upgrade_db", tables);
            var checksums = history("governance_upgrade_db");
            var flyway = org.flywaydb.core.Flyway.configure().dataSource(url, "root", ROOT_PASSWORD)
                .locations("classpath:db/migration").target("6").load();
            assertEquals(1, flyway.migrate().migrationsExecuted);
            assertEquals(before, tableDigests("governance_upgrade_db", tables));
            var after = history("governance_upgrade_db");
            assertEquals(6, after.size());
            checksums.forEach((version, checksum) -> assertEquals(checksum, after.get(version)));
            assertEquals(0, flyway.migrate().migrationsExecuted);
            sql("INSERT INTO governance_upgrade_db.role_assignment(employee_id,role_code,scope_type,scope_id,"
                + "created_at,updated_at) VALUES(1,'POLICY_EDITOR','GLOBAL',0,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
            assertThrows(java.sql.SQLException.class, () -> sql("INSERT INTO governance_upgrade_db.role_assignment"
                + "(employee_id,role_code,scope_type,scope_id,created_at,updated_at) "
                + "VALUES(1,'SECURITY_APPROVER','TEAM',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))"));
        }
    }

    @Test
    @DisplayName("P7迁移基础：五表规范匹配，池默认关闭、资源身份唯一、部署外键和CHECK真实生效")
    void runtimeSchemaEnforcesCapacityAndDeploymentConstraints() throws Exception {
        var root = java.nio.file.Path.of("..");
        String design = java.nio.file.Files.readString(root.resolve("docs/database/core-v07.md"));
        String ddl = design.split("```sql\n", 2)[1].split("```", 2)[0].strip();
        assertEquals(ddl, java.nio.file.Files.readString(root.resolve(
            "aigate-core/src/main/resources/db/migration/V7__invocation_runtime.sql")).strip());
        assertEquals(7, count("flyway_schema_history"));
        for (String table : List.of("capacity_pool_policy", "invocation", "dispatch_permit", "result_report_receipt",
            "reconciliation_case")) {
            assertEquals(0, count(table));
        }
        sql("INSERT INTO capacity_pool_policy(pool_code,scope_kind,resource_identity_code,capacity_evidence_ref,"
            + "max_active,max_waiting,max_queue_ms,max_run_ms,rpm_limit,created_at,updated_at) "
            + "VALUES('TEST_POOL','PROVIDER','TEST_IDENTITY','TEST_ONLY',1,2,1000,10000,60,"
            + "UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        assertEquals("DISABLED", scalar("SELECT status FROM capacity_pool_policy"));
        assertEquals("0", scalar("SELECT admission_epoch FROM capacity_pool_policy"));
        assertThrows(java.sql.SQLException.class, () -> sql("INSERT INTO capacity_pool_policy("
            + "pool_code,scope_kind,resource_identity_code,capacity_evidence_ref,max_active,max_waiting,"
            + "max_queue_ms,max_run_ms,rpm_limit,created_at,updated_at) VALUES('TEST_ALIAS','PROVIDER',"
            + "'TEST_IDENTITY','TEST_ONLY',1,2,1000,10000,60,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))"));
        for (String change : List.of("max_active=0", "max_waiting=-1", "max_queue_ms=0", "max_run_ms=0",
            "rpm_limit=0", "admission_epoch=-1", "status='OPEN'", "scope_kind='FAKE'")) {
            assertThrows(java.sql.SQLException.class, () -> sql("UPDATE capacity_pool_policy SET " + change));
        }
        assertThrows(java.sql.SQLException.class, () -> sql("UPDATE model_deployment SET capacity_pool_id=99999"));
        sql("UPDATE model_deployment SET capacity_pool_id=1");
        assertThrows(java.sql.SQLException.class, () -> sql("DELETE FROM capacity_pool_policy WHERE id=1"));
    }

    @Test
    @DisplayName("P7迁移基础：真实V6历史升级V7，37张旧表及checksum保持，重复迁移不执行")
    void realV6HistorySurvivesV7ForeignKeysAndNewRuntimeTables() throws Exception {
        teamPool("1000.000000", null);
        grant("runtime-upgrade-grant", "100.000000", "2026-11-05T00:00:00Z");
        sql("CREATE DATABASE runtime_upgrade_db");
        String url = MYSQL.getJdbcUrl().replace("/core_db", "/runtime_upgrade_db");
        org.flywaydb.core.Flyway.configure().dataSource(url, "root", ROOT_PASSWORD)
            .locations("classpath:db/migration").target("6").load().migrate();
        var tables = new ArrayList<String>();
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", ROOT_PASSWORD);
            var statement = connection.createStatement()) {
            try (var rows = statement.executeQuery("SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema='runtime_upgrade_db' AND table_name!='flyway_schema_history'")) {
                while (rows.next()) {
                    tables.add(rows.getString(1));
                }
            }
            statement.execute("SET FOREIGN_KEY_CHECKS=0");
            for (String table : tables) {
                statement.execute("TRUNCATE TABLE runtime_upgrade_db." + table);
                statement.execute("INSERT INTO runtime_upgrade_db." + table + " SELECT * FROM core_db." + table);
            }
            statement.execute("SET FOREIGN_KEY_CHECKS=1");
        }
        assertEquals(37, tables.size());
        var before = tableDigests("runtime_upgrade_db", tables);
        var checksums = history("runtime_upgrade_db");
        var flyway = org.flywaydb.core.Flyway.configure().dataSource(url, "root", ROOT_PASSWORD)
            .locations("classpath:db/migration").target("7").load();
        assertEquals(1, flyway.migrate().migrationsExecuted);
        assertEquals(before, tableDigests("runtime_upgrade_db", tables));
        var after = history("runtime_upgrade_db");
        assertEquals(7, after.size());
        checksums.forEach((version, checksum) -> assertEquals(checksum, after.get(version)));
        assertEquals(0, flyway.migrate().migrationsExecuted);
    }

    @AfterEach
    void fakeProviderReceivesNoHttp() {
        if (providerListener != null) {
            providerListener.stop(0);
        }
        assertEquals(0, providerRequests.get(), "P4 must not send Provider HTTP even to the Fake listener");
    }

    @AfterAll
    static void stopDatabase() {
        MYSQL.stop();
    }
}
