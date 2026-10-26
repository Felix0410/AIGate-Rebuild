package com.felix.aigatecore.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aigate.contracts.dto.AgentDTO;
import com.aigate.contracts.dto.AgentEmployeePermissionDTO;
import com.aigate.contracts.dto.EmployeeDTO;
import com.aigate.contracts.dto.TeamDTO;
import com.felix.aigatecore.identity.BootstrapControlDO;
import com.felix.aigatecore.identity.IdentityMapper;
import com.felix.aigatecore.identity.IdentityService;
import com.felix.aigatecore.identity.RequestContext;
import com.felix.aigatecore.security.CredentialCodec;
import com.felix.aigatecore.security.RuntimeSecuritySettings;
import com.felix.aigatecore.security.SecuritySettings;
import com.felix.aigatecore.web.ApiException;
import com.felix.aigatecore.web.ValidatedBody;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * P2 固定时钟验证，精确覆盖数据库微秒时间边界和轮换窗口。
 *
 * @author AIGate
 */
class RuntimeKeyTimeTests {

    private static final Instant NOW = Instant.parse("2026-10-26T00:00:00.123456Z");
    private static final RequestContext ADMIN = new RequestContext("Bearer test", "127.0.0.1", "time-test");

    private final RuntimeIdentityMapper mapper = mock(RuntimeIdentityMapper.class);
    private final IdentityMapper identityMapper = mock(IdentityMapper.class);
    private final IdentityService identityService = mock(IdentityService.class);
    private final RuntimeSecuritySettings settings = mock(RuntimeSecuritySettings.class);

    /**
     * 准备 ACTIVE Owner 与固定时钟，生成主键回填沿用真实 Mapper 的行为。
     */
    private RuntimeIdentityService service() {
        when(identityMapper.lockBootstrapControl()).thenReturn(new BootstrapControlDO(1, NOW));
        when(identityService.requireGlobalAdministrator(ADMIN)).thenReturn(1L);
        when(mapper.lockAgent(2)).thenReturn(new AgentDTO(2, "APP_TEST", "Test", 3, "ACTIVE", 0, NOW, NOW));
        when(mapper.lockEmployee(2)).thenReturn(new EmployeeDTO(2, "ALICE", "Alice", 3, "ACTIVE", 0, NOW, NOW));
        when(identityMapper.selectTeamById(3)).thenReturn(new TeamDTO(3, "Team", "ACTIVE", 0, NOW, NOW));
        when(settings.current(true)).thenReturn(new SecuritySettings.Pepper("agent-v1", new byte[32]));
        when(settings.current(false)).thenReturn(new SecuritySettings.Pepper("employee-v1", new byte[32]));
        when(mapper.insertKey(anyMap())).thenAnswer(invocation -> {
            Map<String, Object> values = invocation.getArgument(0);
            values.put("id", 10L);
            return 1;
        });
        when(mapper.supersedeKey(anyMap())).thenReturn(1);
        return new RuntimeIdentityService(mapper, identityMapper, identityService, settings, new CredentialCodec(),
            Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /**
     * 构造只有 expiresAt 的合法 JSON，请求时间不依赖测试机器运行速度。
     */
    private ValidatedBody expiry(Instant time) {
        return ValidatedBody.parse(("{\"expiresAt\":\"" + time + "\"}").getBytes(StandardCharsets.UTF_8), "expiresAt");
    }

    /**
     * P2-13：固定 Clock 的 1h/90d 两端可签发，越界 1us 即拒绝。
     */
    @Test
    @DisplayName("P2-13：固定 Clock 的 1h/90d 两端可签发，越界 1us 即拒绝")
    void issuanceIncludesBothEndpointsAndRejectsOneMicrosecondOutside() {
        RuntimeIdentityService service = service();
        for (boolean agentKey : List.of(true, false)) {
            // 1、时间窗口两端均合法，签发时间使用同一固定 UTC 时刻。
            for (Instant valid : List.of(NOW.plusSeconds(3600), NOW.plus(90, ChronoUnit.DAYS))) {
                var issued = service.issueKey(ADMIN, agentKey, 2, null, expiry(valid), "boundary-valid");
                assertEquals(NOW, issued.validFrom());
                assertEquals(valid, issued.expiresAt());
            }

            // 2、只差一微秒也不能越过最短和最长有效期。
            for (Instant invalid : List.of(NOW.plusSeconds(3600).minus(1, ChronoUnit.MICROS),
                NOW.plus(90, ChronoUnit.DAYS).plus(1, ChronoUnit.MICROS), NOW.minusSeconds(1))) {
                ApiException failure = assertThrows(ApiException.class,
                    () -> service.issueKey(ADMIN, agentKey, 2, null, expiry(invalid), "boundary-invalid"));
                assertEquals(400, failure.status());
            }
        }
    }

    /**
     * P2-19：生效端点包含，expiresAt/graceExpiresAt 端点立即失效。
     */
    @Test
    @DisplayName("P2-19：生效端点包含，expiresAt/graceExpiresAt 端点立即失效")
    void authenticationWindowsAreHalfOpenAtMicrosecondPrecision() {
        Instant expiry = NOW.plusSeconds(7200);
        RuntimeKeyDO natural = new RuntimeKeyDO(1, 2, expiry, NOW, null, null, null, null);
        assertFalse(natural.authenticatableAt(NOW.minus(1, ChronoUnit.MICROS)));
        assertTrue(natural.authenticatableAt(NOW));
        assertTrue(natural.authenticatableAt(expiry.minus(1, ChronoUnit.MICROS)));
        assertFalse(natural.authenticatableAt(expiry));

        Instant grace = NOW.plusSeconds(3600);
        RuntimeKeyDO rotated = new RuntimeKeyDO(1, 2, expiry, NOW, NOW, grace, null, null);
        assertTrue(rotated.authenticatableAt(grace.minus(1, ChronoUnit.MICROS)));
        assertFalse(rotated.authenticatableAt(grace));
        assertTrue(natural.authenticatableAt(grace));
        RuntimeKeyDO revoked = new RuntimeKeyDO(1, 2, expiry, NOW, NOW, grace, NOW, null);
        assertFalse(revoked.authenticatableAt(NOW));
    }

    /**
     * P2-19：真实认证方法在 expiresAt/graceExpiresAt 前 1us 成功，恰到边界返回 401。
     */
    @Test
    @DisplayName("P2-19：真实认证方法在 expiresAt/graceExpiresAt 前 1us 成功，恰到边界返回 401")
    void verificationReturnsUnauthenticatedAtEachExactDeadline() {
        service();
        when(mapper.selectAgent(2)).thenReturn(new AgentDTO(2, "APP_TEST", "Test", 3, "ACTIVE", 0, NOW, NOW));
        when(identityMapper.selectEmployeeById(2)).thenReturn(
            new EmployeeDTO(2, "ALICE", "Alice", 3, "ACTIVE", 0, NOW, NOW));
        when(mapper.selectPermission(anyMap())).thenReturn(
            new AgentEmployeePermissionDTO(2, 2, "GRANTED", 0, NOW, null));
        var pepper = new SecuritySettings.Pepper("test-version", new byte[32]);
        when(settings.accepted(true)).thenReturn(List.of(pepper));
        when(settings.accepted(false)).thenReturn(List.of(pepper));

        for (boolean agentKey : List.of(true, false)) {
            for (boolean grace : List.of(true, false)) {
                RuntimeKeyDO ending = new RuntimeKeyDO(4, 2, grace ? NOW.plusSeconds(3600) : NOW,
                    NOW.minusSeconds(3600), grace ? NOW.minusSeconds(1) : null, grace ? NOW : null, null, null);
                RuntimeKeyDO ongoing = new RuntimeKeyDO(5, 2, NOW.plusSeconds(7200), NOW.minusSeconds(1),
                    null, null, null, null);
                when(mapper.selectByDigest(anyMap())).thenAnswer(invocation -> {
                    Map<String, Object> query = invocation.getArgument(0);
                    return Boolean.valueOf(agentKey).equals(query.get("agentKey")) ? ending : ongoing;
                });
                RuntimeIdentityService before = new RuntimeIdentityService(mapper, identityMapper, identityService,
                    settings, new CredentialCodec(), Clock.fixed(NOW.minus(1, ChronoUnit.MICROS), ZoneOffset.UTC));
                assertEquals(2, before.verify("Bearer agk_" + "A".repeat(43), "empk_" + "B".repeat(43)).agentId());
                RuntimeIdentityService deadline = new RuntimeIdentityService(mapper, identityMapper, identityService,
                    settings, new CredentialCodec(), Clock.fixed(NOW, ZoneOffset.UTC));
                assertEquals(401, assertThrows(ApiException.class,
                    () -> deadline.verify("Bearer agk_" + "A".repeat(43), "empk_" + "B".repeat(43))).status());
            }
        }
    }

    /**
     * P2-18、22：宽限期受 24h、自然到期和已有窗口三重限制。
     */
    @Test
    @DisplayName("P2-18、22：宽限期受 24h、自然到期和已有窗口三重限制")
    void rotationNeverExtendsNaturalOrExistingGraceDeadline() {
        RuntimeIdentityService service = service();
        for (boolean agentKey : List.of(true, false)) {
            // 逐项改变旧凭证窗口，检查返回的宽限截止与最早截止时间完全相同。
            for (long seconds : List.of(3600L, 86400L, 172800L)) {
                Instant naturalExpiry = NOW.plusSeconds(seconds);
                when(mapper.lockKey(anyMap())).thenReturn(
                    new RuntimeKeyDO(4, 2, naturalExpiry, NOW.minusSeconds(1), null, null, null, null));
                var issued = service.issueKey(ADMIN, agentKey, 2, 4L, expiry(NOW.plus(2, ChronoUnit.DAYS)),
                    "rotate-time-boundary");
                assertEquals(NOW.plusSeconds(Math.min(seconds, 86400)), issued.oldCredentialGraceExpiresAt());
            }
            Instant existingGrace = NOW.plusSeconds(30);
            when(mapper.lockKey(anyMap())).thenReturn(new RuntimeKeyDO(4, 2, NOW.plus(2, ChronoUnit.DAYS),
                NOW.minusSeconds(3600), NOW.minusSeconds(10), existingGrace, null, null));
            assertEquals(existingGrace, service.issueKey(ADMIN, agentKey, 2, 4L,
                expiry(NOW.plus(2, ChronoUnit.DAYS)), "preserve-grace-window").oldCredentialGraceExpiresAt());

            // 已过期或已吊销的来源不能再次轮换。
            for (RuntimeKeyDO invalid : List.of(
                new RuntimeKeyDO(4, 2, NOW, NOW.minusSeconds(3600), null, null, null, null),
                new RuntimeKeyDO(4, 2, NOW.plusSeconds(3600), NOW.minusSeconds(1), null, null, NOW, null))) {
                when(mapper.lockKey(anyMap())).thenReturn(invalid);
                assertEquals("INVALID_STATE", assertThrows(ApiException.class,
                    () -> service.issueKey(ADMIN, agentKey, 2, 4L, expiry(NOW.plus(2, ChronoUnit.DAYS)),
                        "invalid-old-credential"))
                    .code());
            }
        }
    }
}
