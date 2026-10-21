# 10A · Acceptance Matrix

The local coding AI must implement one Phase at a time and attach reproducible evidence. Do not use a green build from the former project as proof.

| Scenario | Phase | Expected result |
|---|---|---|
| Team/Employee CRUD from empty Flyway database | P1 | Restart and integration test pass |
| Managed Agent key revoked while employee credential valid | P2/P4 | All new runtime requests rejected before Provider call |
| Agent valid but employee credential absent or belongs to another Employee | P4 | HTTP 401/403, fake Provider request count remains 0 |
| Employee authorized to use Agent, logical Model missing from allowlist | P4 | Rejected before any provider call or charge |
| Two concurrent invocations use same limited weekly bucket | P5 | No negative available balance, unique settlements |
| Duplicate idempotency key is retried after a partial/timeout upstream call | P5 | No second paid Provider request |
| Provider timeout after potentially billable call without final usage | P5/P8 | PENDING_RECONCILIATION, traceable hold not assumed free |
| Company timezone crosses Monday midnight | P5 | New weekly buckets once, prior historical ledger unchanged |
| Team grants 1000 points to another Team Employee | P6 | Issuer pool debited once, recipient grant +1000 once |
| Duplicate approval submitted concurrently | P6 | One successful issuance, one expense event |
| Grant expires with 700 points unused | P6 | Remaining unusable, no refund to issuing Team |
| Employee normal points all spent, company fallback available | P7 | Approved cheaper fallback Model, low priority/limited capacity |
| Employee and company fallback caps depleted | P7 | Paid Provider never called; self-host only if approved/available |
| Two Gateway replicas send calls against same admission allowance | P7 | Global limit enforced with Redis atomic operation |
| SSE receives some chunks but client disconnects before trusted usage | P8 | Upstream canceled if possible; unknown cost pending |
| Deployment is unhealthy while another serves same logical Model | P8 | Healthy deployment selected, not unauthorized different Model |
| Emergency approval expired or exceeds hard budget | P9 | Request rejected or treated as ordinary, cannot bypass ACL |
| Safety rule blocks secret pattern before Provider egress | P10 | Provider request count 0, audit metadata but no raw prompt |
| RabbitMQ unavailable after successful ledger settlement | P11 | Ledger still correct; Outbox pending and later replayed |
| The same settled event delivered twice to FinOps | P11 | One aggregated cost increment only |
| Cross-Team issuance 1000; Employee spend 300 | P11 | Team allocation 1000, Employee usage 300, Provider cost separate |
| Synthetic high-concurrency SSE workload on one vs two replicas | P12 | Recorded host, workload, p95/p99, threads, CPU and connection stats |
| Quota process unavailable after optional service extraction | P12 conditional | No unprepaid paid Provider calls; tested failure semantics |

## Phase signoff evidence

For each task: actual Git commit, changed files, migration IDs, commands executed, tests passed/failed/skipped, security/finance invariants, infrastructure used, risks and technical debt. Total service count is not an acceptance metric; measurable isolation and correct accounting are.
