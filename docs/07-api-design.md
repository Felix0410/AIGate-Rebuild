# 07 - API Contract

All JSON APIs use UTF-8, UTC timestamps, traceId, and an error object with code and message.

## Management routes

The /api/v1 namespace manages teams, employees, managed agents, API keys, providers, logical models, model deployments, effective price versions, employee-agent authorizations, agent-model allowlists, team grant requests, team grants, emergency approval, safety policy, invocation history, and FinOps reports. Collection queries must support pagination and role-aware access.

## Runtime route

POST /v1/chat/completions accepts a logical model code, messages, max_tokens, stream and optional stream_options. The call must verify one managed Agent credential and one independent Employee credential. Agent model selection and staff authorization are checked before upstream calls.

The request carries an idempotency key. Its scope includes the authenticated employee and agent, and duplicate keys with different payload digests return conflict. An in-progress request cannot be resent to the paid provider.

When stream=false, the response includes OpenAI-compatible choices, model and trusted usage. When stream=true, the response is SSE and emits compatible incremental chunk records followed by a completion terminator. Client cancellation attempts to close upstream I/O. Missing trustworthy final usage becomes pending reconciliation, never automatically free.

## Representative status codes

400 invalid request; 401 missing or revoked credential; 403 unauthorized agent/model or safety block; 402 no authorized paid balance; 409 concurrent/duplicate request conflict; 429 admission throttled; 503 upstream unavailable; 504 upstream timeout.

Management writes require appropriate roles; employee self-service is limited to that employee; manager grant operations can debit only the manager's authorized team pool. Responses and logs must not reveal secrets, raw prompts or upstream credentials.

## Route ownership

Identity module: teams, employees, agents, permissions and registries.
Quota module: balances, grants, approvals, emergency authorizations, reconciliation.
Governance module: policy publication and decision audits.
Analytics module: employee usage, team-issued quota expense and provider spend estimate.
Gateway module: chat completion request and invocation records.

The exact DTO fields, update methods and status machines must conform to 05-domain-model.md and 06-database-design.md. Local coding AI cannot silently invent a different ownership model.
