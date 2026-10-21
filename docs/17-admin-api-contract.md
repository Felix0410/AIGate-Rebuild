# 17 · Administration API and DTO Contract

Status: DESIGN DEFAULT. This complements 07-api-design.md and 07a-contract-details.md. All routes require authenticated, appropriately scoped management identity. JSON property names below are fixed for initial implementation; change only through the design owner.

## Shared conventions

Base path /api/v1. Collection GET returns {items:[...],page:1,size:20,total:0}. Default page=1, size=20, upper bound=100. All success objects include id, createdAt and updatedAt as UTC ISO 8601 timestamps. POST uses 201 and Location header; PATCH uses 200 with full updated representation. DELETE of a credential revokes it and responds 204, not a physical purge.

Error JSON is {code,message,traceId,details?}. DTO validation: names 1–128 Unicode chars, resource codes 1–64 ASCII code-safe chars, positive point amounts DECIMAL string with <=6 fractional digits, secrets only once on creation. All list and mutation APIs check principal scope before database action.

## Identity management (P1/P2/P4)

POST /teams: {name:string}, returns {id,name,status:"ACTIVE",createdAt,updatedAt}. GET /teams?page&size lists. PATCH /teams/{id}: {name?,status?}. Status ACTIVE/DISABLED; cannot delete a Team with employees or outstanding funds.
POST /employees: {employeeCode:string,displayName:string,primaryTeamId:long}, returns {id,employeeCode,displayName,primaryTeamId,status,...}. GET /employees and GET /employees/{id}; PATCH /employees/{id}: {displayName?,primaryTeamId?,status?}. EmployeeCode globally unique; primary Team must exist and be active. All historical usage retains primaryTeamId snapshot.
POST /employees/{id}/credentials: {expiresAt:timestamp}, returns {credentialId,employeeId,key:empk_...,expiresAt}; only creation includes key. DELETE /employees/{id}/credentials/{credentialId} revokes. GET credential metadata is secret-free.
POST /agents: {teamId:long,name:string}, returns {id,teamId,name,status}; GET /agents, GET /agents/{id}, PATCH /agents/{id}: {name?,status?,fallbackModelId?}. Agent has a single Application domain record; no Agent workflow editor.
POST /agents/{id}/keys: {expiresAt:timestamp}, returns {keyId,key:agk_...,expiresAt}; DELETE /agents/{id}/keys/{keyId} revokes.
PUT /employees/{employeeId}/agents/{agentId}: {enabled:boolean}; PUT /agents/{agentId}/models/{modelId}: {enabled:boolean}. Both APIs are idempotent and return current permission state, do not permit a disabled Agent or Employee to invoke.

## Model catalog (P2/P5/P8)

POST /providers: {code:string,name:string,type:"OPENAI_COMPATIBLE",status:"ACTIVE"|"DISABLED"}. GET /providers and PATCH /providers/{id}.
POST /models: {code:string,name:string,maxInputTokens:integer,maxOutputTokens:integer,status}. GET /models and PATCH /models/{id}.
POST /deployments: {modelId:long,providerId:long,remoteModelName:string,endpointUrl:string,providerCredential:string,weight:integer,status}; GET /deployments, PATCH /deployments/{id} for status/weight/name/endpoint and rotated encrypted providerCredential. All raw credentials excluded from responses. weight is 0–1000; disabled deployments excluded from routing. URL must pass Provider whitelist/SSRF rules.
POST /prices: {targetType:"LOGICAL_MODEL"|"DEPLOYMENT",targetId:long,priceType:"INTERNAL_POINTS"|"PROVIDER_PURCHASE",currency:"POINTS"|ISO4217,inputPerMillion:string,outputPerMillion:string,cachePerMillion?:string,validFrom:timestamp}; created prices are immutable and validity must not overlap the same target/type/currency schedule. Missing model prices prohibit paid execution.

## Quota and grants (P5/P6/P7/P9)

GET /me/quotas returns {employeeId,periodStart,periodEnd,agentFree:[{agentId,availablePoints}],personalAvailablePoints,grants:[{grantId,issuerTeamId,availablePoints,expiresAt}],fallbackAvailablePoints?}. Expose only the caller's own balances.
POST /grant-requests: {issuerTeamId:long,requestedPoints:string,reason:string}, returns PENDING with request ID; GET /grant-requests scope is own applications or manager's own Team.
POST /grant-requests/{id}/approve: {decision:"APPROVE"|"REJECT",expiresAt?:timestamp,comment?:string}, with Idempotency-Key header. Approve atomically deducts Team pool and creates exactly one Grant; reject does not debit.
POST /teams/{id}/grants: {employeeId:long,points:string,expiresAt:timestamp,reason:string} with Idempotency-Key; returns {grantId,issuerTeamId,recipientEmployeeId,issuedPoints,expiresAt}. TeamManager must control this Team.
GET /teams/{id}/quota returns {teamId,balancePoints,reservedPoints,availablePoints} with authorized manager or admin role.
GET /invocations?employeeId?&agentId?&modelId?&status?&from?&to?&page&size returns safe metadata only.
POST /emergencies: {employeeId,agentId,taskReference,requestedPoints,validUntil,reason}; POST /emergencies/{id}/approve: {decision,approvedPoints?} with Idempotency-Key; admin approval required, hard emergency budget enforced.
GET /reconciliations?state&... is admin-only; POST /reconciliations/{id}/resolve: {resolution:"SETTLE"|"RELEASE",trustedEvidenceRef:string,confirmedTokenUsage?}. Every decision audited; RELEASE requires demonstrable zero billable use.

## Governance and FinOps (P10/P11)

POST /safety/policies: {name,scope:{agentIds?,modelIds?,providerIds?,employeeIds?},defaultDecision:"ALLOW"|"BLOCK",ruleSet:[{ruleType,patternClass,action}]}; GET /safety/policies, GET /safety/policies/{id}; POST /safety/policies/{id}/publish: {expectedDraftVersion}, returns {publishedVersion,publishedAt}; published versions immutable.
GET /safety/decisions?from&to&riskType&page&size returns actorId,decisionId,policyVersion,riskType,modelRef,timestamp; never raw content.
GET /finops/reports?from&to&groupBy=EMPLOYEE|TEAM|AGENT|MODEL|DEPLOYMENT&metric=EMPLOYEE_USAGE_POINTS|TEAM_GRANTS_ISSUED_POINTS|PROVIDER_COST_ESTIMATE&currency? returns {metric,unit,currency?,period,items:[{groupId,value}],"dataCompleteness":"FINAL"|"PARTIAL"}. Do not combine all three metrics into one company currency value.
GET /finops/alerts?from&to&state&page&size returns budget/anomaly alert metadata.

## Design boundary

This document fixes public endpoints and minimum DTO fields. Additional internal metadata/constraints must preserve 05-domain-model.md, 06-database-design.md, 16-security-auth.md and 02-business-rules.md. Local AI may not silently introduce endpoints or claim full P10 safety before P10. Phase contracts remain the authority for which route is implemented when.
