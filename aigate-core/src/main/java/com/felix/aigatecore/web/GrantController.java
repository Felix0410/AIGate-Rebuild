package com.felix.aigatecore.web;

import static com.felix.aigatecore.web.ManagementRequestParameters.context;
import static com.felix.aigatecore.web.ManagementRequestParameters.query;

import com.aigate.contracts.dto.GrantDTO;
import com.aigate.contracts.dto.PageDTO;
import com.aigate.contracts.dto.TeamPoolDTO;
import com.felix.aigatecore.finance.GrantService;
import com.felix.aigatecore.identity.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** P5六个精确管理方法；跨Team发行和领取的权限由Core当前身份校验。 */
@RestController
public class GrantController {
    private final GrantService service;

    /**
     * 装配P5服务。
     *
     * @param service Pool、Grant与永久收据事务
     */
    public GrantController(GrantService service) {
        this.service = service;
    }

    /** 创建或CAS更新团队池，创建null哨兵仅用于expectedVersion。 */
    @PutMapping(path = "/api/v1/teams/{teamId}/pool/{periodStart}", consumes = "application/json")
    ResponseEntity<TeamPoolDTO> putPool(@PathVariable String teamId, @PathVariable String periodStart,
        @RequestBody byte[] bytes, HttpServletRequest request) {
        var result = service.putPool(context(request), ValidatedBody.pathId(teamId, false), periodStart,
            ValidatedBody.parseAllowingNull(bytes, Set.of("expectedVersion"),
                "limitPoints", "periodEnd", "expectedVersion"));
        return ResponseEntity.status(result.created() ? 201 : 200).body(result.pool());
    }

    /** Admin或本Team Manager读取指定周期。 */
    @GetMapping("/api/v1/teams/{teamId}/pool")
    TeamPoolDTO pool(@PathVariable String teamId, HttpServletRequest request) {
        return service.pool(RequestContext.from(request), ValidatedBody.pathId(teamId, false), query(request));
    }

    /** 收据头必须唯一；不由容器默认选取重复Header中的一个。 */
    @PostMapping(path = "/api/v1/teams/{teamId}/grants", consumes = "application/json")
    ResponseEntity<GrantDTO> issue(@PathVariable String teamId, @RequestBody byte[] bytes, HttpServletRequest request) {
        var keys = Collections.list(request.getHeaders("Idempotency-Key"));
        if (keys.size() != 1) {
            throw ApiException.invalid();
        }
        return ResponseEntity.status(201).body(service.issue(context(request), ValidatedBody.pathId(teamId, false),
            ValidatedBody.parse(bytes, "recipientEmployeeId", "points", "expiresAt", "reasonCode"), keys.getFirst()));
    }

    /** 发行明细始终按路径Team授权并在SQL中分页。 */
    @GetMapping("/api/v1/teams/{teamId}/grants")
    PageDTO<GrantDTO> issued(@PathVariable String teamId, HttpServletRequest request) {
        return service.issued(RequestContext.from(request), ValidatedBody.pathId(teamId, false), query(request));
    }

    /** 接收者身份只来自mgt_本人。 */
    @GetMapping("/api/v1/employees/me/grants")
    PageDTO<GrantDTO> received(HttpServletRequest request) {
        return service.received(RequestContext.from(request), query(request));
    }

    /** 重复撤销仍为204，原理由与发行账不可覆盖。 */
    @PostMapping(path = "/api/v1/teams/{teamId}/grants/{grantId}/revoke", consumes = "application/json")
    ResponseEntity<Void> revoke(@PathVariable String teamId, @PathVariable String grantId, @RequestBody byte[] bytes,
        HttpServletRequest request) {
        service.revoke(context(request), ValidatedBody.pathId(teamId, false), ValidatedBody.pathId(grantId, false),
            ValidatedBody.parse(bytes, "reasonCode"));
        return ResponseEntity.noContent().build();
    }
}
