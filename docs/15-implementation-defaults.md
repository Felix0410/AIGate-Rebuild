# 15 · Implementation Defaults and Frozen Boundaries

This file resolves implementation choices that the local coding AI must not improvise. A default is a design choice for a demo system, not a claim that a real company uses these policies.

## Baseline and bootstrapping

- Java 21, Spring Boot 4.1.x with compatible MyBatis-Plus Boot4 starter, Maven, Spring MVC, MySQL 8.4, Flyway and Testcontainers. Single deployable app.
- First admin account is bootstrapped from a noncommitted environment configuration, stored using the app's chosen secure authentication implementation. After P4, management RBAC uses authenticated employee/role context; never trust caller-selected roles.
- Agent credential: server-created random high-entropy opaque API key, persisted only as one-way digest with visible short prefix, expiry and revocation status.
- Employee credential: independent random high-entropy opaque API key with the same security properties. No employeeId-only authentication.
- Provider credential is encrypted at rest and never sent to nonallowlisted upstream endpoints. No actual provider secret should be included in docs or tests.
- Company billing timezone is configurable and defaults to Asia/Shanghai; all instants persisted in UTC.
- Technical base path /api/v1 for admin APIs and /v1/chat/completions for runtime requests.
- Initial Provider adapter supports OpenAI-style Chat Completions. Other model protocols require a new adapter contract and test.
- Time and randomness must be injectable in tests; use Clock, stable unique request IDs, and database uniqueness constraints.

## Money and tokens

- Store internal points DECIMAL(20,6); provider charge estimate DECIMAL(20,8) in the original vendor currency. Use per-million-token price units, retain input/output/cache categories and effective price version IDs.
- Rounding: HALF_UP per invocation, after sum of the separate categories; use BigDecimal in Java.
- Quota priority: Employee-Agent free weekly bucket → Employee basic weekly bucket → Employee Grants ordered by expiration then ID → approved and bounded fallback/emergency funding.
- An authenticated call must include a bounded output token limit and satisfy an approved risk reservation policy before a paid request can begin. A merely guessed token estimate is not evidence of a hard budget guarantee.
- Unknown Provider usage: pending reconciliation, not automatically zero.
- Internal issued team quota expense and actual employee usage cannot be added to assert total company provider procurement cost.

## Async and infrastructure

- RabbitMQ is the proposed default messaging broker if/when the P11 asynchronous workload warrants it; initially do not add Kafka or RocketMQ.
- Outbox commit is atomic with Quota ledger updates; event consumer is at-least-once and idempotent.
- Redis is for demonstrated multi-instance shared limits, never for authoritative point balances.
- Spring MVC SSE first; WebFlux remains conditional. Spring Cloud, Nacos and five independently deployed services are conditional on P12 evidence.
- No Elasticsearch, RAG ingestion or agent runtime unless an explicit new business approval supersedes the current project scope.

## Configuration not invented by design

Corporate weekly point amounts, Team initial pool, per-Employee thresholds, corporate paid cap, emergency cap, model pricing, Provider endpoints and credentials, rate limits, safety rule patterns, and capacity reservations are configuration or seed data for each Phase. They must be set explicitly in an environment/test fixture before executing a paid Provider call. Missing hard-limit config on a paid route fails closed.
