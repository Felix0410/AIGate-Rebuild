# 06A · Billing Transaction & Recovery Contract

This document supplements 06-database-design.md. It is the exact invariant contract for the coding AI; schema field naming can differ only with documented equivalent semantics and review.

## Transaction A: Grant issuance

Input: unique grantOperationId, issuingTeamId, recipientEmployeeId, points, expiry, approvedBy.
Validate authorized manager, expiry in future, points positive and within team policy. Lock the issuing TeamPool row. If previously committed operationId exists, return that result without another debit. Require balance >= points. Update TeamPool balance -= points, insert Grant with remainingPoints=points, create immutable issuing Team allocation ledger entry and a durable Outbox event. Commit together. Any failure rolls back all changes; a repeated approval must not grant twice.

## Transaction B: Invocation reservation

Input: trusted Employee, Agent, logical Model, price versions, normalized request fingerprint and unique idempotency key. Validate Employee-Agent and Agent-Model permissions and upstream budget risk limit before any paid request. Create Invocation CREATED. Lock funding sources in deterministic order: employee-agent weekly free, employee weekly base, nonexpired grants ordered by expiry/id, eligible corporate fallback/emergency pools. Check all balances and policy limits. Reserve the bounded maximum liability as a set of ReservationAllocation rows; either all succeed or none. Commit before upstream network I/O.

All paid traffic needs a valid maximum risk bound. Require max_tokens and model/provider input/output constraints; if exact worst-case cost cannot be bounded, fail closed. Upstream can exceed reserved liability due to provider violation; surface as reconciliation/anomaly, not hidden negative balance.

## Transaction C: Final settlement

After the Provider returns trustworthy classified token counts, use the **price versions captured for this invocation**. Lock the reservation and its allocations. If already SETTLED, return original result; otherwise settle used points through reserved allocations in funding priority, release unused holds, write one immutable Settlement and paired ledger entries, set Invocation SETTLED, add InvocationSettled Outbox event. Commit atomically. Never recompute old prices from the current price registry.

## Transaction D: Unknown or canceled upstream

If upstream definitely was not contacted, release the entire reservation and mark CANCELLED. If call may have reached a billable Provider but no authoritative final usage is available (timeout, SSE cancellation, malformed usage), mark PENDING_RECONCILIATION, retain the bounded unsettled hold and create a case with reason/timestamp. Authorized reconciliation can settle based on trusted evidence, or release after proof of zero billed usage. The case is immutable/audited; no ordinary timer may silently cancel unknown cost.

## Price examples and anti-double-count

Token points = sum(classified token count × effective internal points per million / 1000000). Provider spend estimate = sum(classified token count × effective provider unit price / 1000000); retain original currency. Suggested HALF_UP six decimals for points and eight decimals for currency per finalized invocation. Use Java BigDecimal and MySQL DECIMAL, never floating-point binary arithmetic.

Grant 1000 points from Team B to employee in Team A; Team B allocation expense =1000 immediately, Employee usage may later be 300. Employee usage and Team allocation expense are separate views, not company cash cost 1300.
