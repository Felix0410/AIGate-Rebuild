# ADR-001 · Start with a modular monolith

Status: Proposed design baseline. Date: 2026-10-21.

## Background

AIGate contains five distinct business domains: model gateway, identity and control, authoritative quota and billing, safety governance, and FinOps analytics. The user wants deep Java backend learning with reliable business behavior.

## Problem

Deploying every domain as a separate service on day one adds network dependencies, authentication context propagation, distributed failure handling, and more operational work before there is evidence of independent scaling needs.

## Alternatives

1. One unstructured application.
2. One modular monolith with strict package and data ownership.
3. Five independently deployable services immediately.

## Decision

Start with option 2. Keep one Spring Boot application and one authoritative MySQL data source, with clear domain facades. Run a multi-replica monolith under a controlled SSE and billing workload before considering extraction.

## Benefits and costs

Benefits: fastest reliable happy path, easy ACID transactions, fewer deployment failure modes, understandable unit and integration tests. Costs: modules initially deploy together and need package/ownership discipline. Future extraction requires explicit boundaries and data migration.

## Future impact

P12 may independently scale Gateway Runtime, Control, Billing, Governance and FinOps only if measurements show the benefit outweighs RPC and operations cost. A decision not to split is also acceptable when supported by evidence.
