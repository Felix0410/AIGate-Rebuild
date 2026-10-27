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

/** P4真实MySQL行锁/事务及Core HTTP测试，所有数据只在临时容器；没有Provider客户端。 */
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
    private FinancePeriods periods;
    @Autowired
    private MutableClock clock;
    private RequestContext admin;
    private String adminKey;
    private com.sun.net.httpserver.HttpServer providerListener;
    private final java.util.concurrent.atomic.AtomicInteger providerRequests =
        new java.util.concurrent.atomic.AtomicInteger();

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
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("X-AIGate-Service-Authorization", "Bearer " + SERVICE)
            .header("X-AIGate-Client-Address", "127.0.0.1")
            .header("Idempotency-Key", "finance-fixture-bootstrap");
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
        assertEquals(4, count("flyway_schema_history"));
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
