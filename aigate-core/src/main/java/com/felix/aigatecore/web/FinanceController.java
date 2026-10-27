package com.felix.aigatecore.web;

import static com.felix.aigatecore.web.ManagementRequestParameters.context;
import static com.felix.aigatecore.web.ManagementRequestParameters.query;
import static com.felix.aigatecore.web.ManagementRequestParameters.requireNoBody;

import com.aigate.contracts.dto.EmployeeQuotaDTO;
import com.aigate.contracts.dto.FinanceOperationDTO;
import com.aigate.contracts.dto.PageDTO;
import com.aigate.contracts.dto.ProviderBudgetDTO;
import com.aigate.contracts.dto.QuotaPolicyDTO;
import com.felix.aigatecore.finance.FinanceManagementService;
import com.felix.aigatecore.identity.RequestContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** P4八个管理方法；Fake结算门面没有HTTP映射。 */
@RestController
public class FinanceController {
    private final FinanceManagementService service;

    /**
     * 注入管理事务服务。
     *
     * @param service P4管理服务
     */
    public FinanceController(FinanceManagementService service) {
        this.service = service;
    }

    /** 创建额度策略草稿。 */
    @PostMapping(path = "/api/v1/quotas/policies", consumes = "application/json")
    ResponseEntity<QuotaPolicyDTO> createPolicy(@RequestBody byte[] bytes, HttpServletRequest request) {
        return ResponseEntity.status(201).body(service.createPolicy(context(request), ValidatedBody.parse(bytes,
            "scopeType", "scopeId", "weeklyLimitPoints", "effectiveFrom")));
    }

    /** 发布期望修订。 */
    @PostMapping(path = "/api/v1/quotas/policies/{policyId}/publish", consumes = "application/json")
    QuotaPolicyDTO publishPolicy(@PathVariable String policyId, @RequestBody byte[] bytes, HttpServletRequest request) {
        return service.publishPolicy(context(request), ValidatedBody.pathId(policyId, false),
            ValidatedBody.parse(bytes, "expectedRevision"));
    }

    /** 分页读取策略。 */
    @GetMapping("/api/v1/quotas/policies")
    PageDTO<QuotaPolicyDTO> policies(HttpServletRequest request) {
        return service.listPolicies(RequestContext.from(request), query(request));
    }

    /** 读取原周冻结账户。 */
    @GetMapping("/api/v1/employees/{employeeId}/quotas")
    EmployeeQuotaDTO quotas(@PathVariable String employeeId, HttpServletRequest request) {
        return service.quotas(RequestContext.from(request), ValidatedBody.pathId(employeeId, false), query(request));
    }

    /** 创建或CAS更新原币种总预算及两个分区。 */
    @PutMapping(path = "/api/v1/provider-budgets/{currency}/{month}", consumes = "application/json")
    ResponseEntity<ProviderBudgetDTO> putBudget(@PathVariable String currency, @PathVariable String month,
        @RequestBody byte[] bytes, HttpServletRequest request) {
        var result = service.putBudget(context(request), currency, month, ValidatedBody.parseAllowingNull(bytes,
            Set.of("expectedVersion"), "limitAmount", "normalLimitAmount", "emergencyLimitAmount", "expectedVersion"));
        return ResponseEntity.status(result.created() ? 201 : 200).body(result.budget());
    }

    /** 分页读取各币种预算。 */
    @GetMapping("/api/v1/provider-budgets")
    PageDTO<ProviderBudgetDTO> budgets(HttpServletRequest request) {
        return service.listBudgets(RequestContext.from(request), query(request));
    }

    /** 缺少月份记录时返回404。 */
    @GetMapping("/api/v1/provider-budgets/{currency}/{month}")
    ProviderBudgetDTO budget(@PathVariable String currency, @PathVariable String month, HttpServletRequest request) {
        requireNoBody(request);
        return service.getBudget(context(request), currency, month);
    }

    /** 管理诊断不返回秘密或用量证据。 */
    @GetMapping("/api/v1/finance-operations/{operationKey}")
    FinanceOperationDTO operation(@PathVariable String operationKey, HttpServletRequest request) {
        requireNoBody(request);
        return service.operation(context(request), operationKey);
    }
}
