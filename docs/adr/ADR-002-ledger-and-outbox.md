# ADR-002 · Authoritative ledger and asynchronous analytics

Status: Proposed technical decision; user approved accounting semantics, not broker installation.
Date: 2026-10-21.

## Background

Each real Employee can spend free points, personal points and time-bound Team grants. A Team allocation is debited when issued. Usage fees are calculated from trusted token usage, while separate Provider purchase prices determine an external cost estimate.

## Problem

Concurrent requests, retries, canceled SSE streams and broker outages must never create duplicate or fabricated charges. FinOps reporting may be delayed but accounting must remain authoritative.

## Alternatives

A. Store balances in Redis and send each debit to an MQ.
B. Update MySQL accounts and analytics synchronously in one database transaction.
C. Keep authoritative MySQL transactions and an Outbox, then asynchronously publish immutable settled events to a broker.

## Decision

Use C when asynchronous processing becomes necessary. Within the modular monolith, reserve and settle balances in short MySQL transactions. The event and its ledger change commit together. A publisher may use RabbitMQ, with publisher confirms and consumer ACK, and report consumers deduplicate by event ID.

RabbitMQ is the current design default, chosen for notifications and cost projections. Kafka and RocketMQ remain alternatives requiring business and compatibility review.

## Benefits

Transactional accuracy, resilient event delivery after temporary broker failure, independent analytic scaling, clear ownership and replay from authoritative records.

## Costs

Extra Outbox/Inbox tables, at-least-once delivery, duplicates and retry/DLQ maintenance. The broker does not guarantee exactly-once deductions across databases. Analytic views are eventually consistent and must be reconciled.

## Future impact

When Quota is extracted, it retains sole write ownership of its ledger. FinOps consumes derived events; a queue outage does not authorize free Provider calls or cause loss of committed ledger activity.
