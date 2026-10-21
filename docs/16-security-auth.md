# 16 · Security, Credentials and Authorization Contract

Status: DESIGN DEFAULT, not implemented. These explicit choices prevent the local coding AI from inventing its own authentication scheme.

## Trust boundary

Administrative management requests and Agent model-runtime requests use separate Spring Security filter chains. Runtime requires two independently verified principals: ManagedAgent via its own high-entropy opaque key and Employee via a separately issued key. Caller-supplied employeeId and agentId are never authorization sources.

## Bootstrap management

At initial P1 boot, configure one bootstrap platform administrator secret outside Git via AIGATE_BOOTSTRAP_ADMIN_KEY, require length >=32 random bytes equivalent, and reject non-test startup when absent. Accept it only on administrative /api/v1 routes as Authorization: Bearer adm_<opaque>. Admin authentication uses a constant-time digest comparison against a safely configured secret digest, never prints the credential, and has a test fixture only for CI. It cannot be used to call /v1/chat/completions. P4 adds employee-linked roles/manager scopes; production bootstrap secret is then restricted to break-glass administration and rotatable. Changing this credential scheme requires an ADR, not local AI improvisation.

## Runtime credentials

Agent API key format agk_<random32bytes>, Employee key format empk_<random32bytes>, both generated server-side by SecureRandom and rendered once only when created. Database stores prefix, HMAC-SHA256 fingerprint using a secret pepper from environment, issued/expiry/revocation metadata. No token plaintext is stored. Pepper rotation is an explicit future migration; initial deployment must preserve the pepper securely. Secret normalization and hash lookup must be identical for both types but belong to separate credential repositories.

Headers: Authorization: Bearer agk_<opaque> and X-Employee-Key: empk_<opaque>. Never authenticate an Employee based on request body, Agent-sent employeeId, forwarded proxy header or model selection. Test that revoked, expired or disabled identities fail before Provider egress.

## Roles and scopes

ADMIN: any authorized management resource; can publish policy, manage models/prices and perform audited reconciliation.
TEAM_MANAGER: only its assigned teams' grant requests, Team pools and team reports; cannot approve own role assignments or unrestricted paid budgets.
EMPLOYEE: own quotas, own grant requests and own invocation metadata; no access to another employee's account.
FINOPS_READER: aggregate read-only expense/usage views; no price mutation or other credential creation.
Agent runtime key: model invocation only; never management API or Employee-only self-service.

Authority is server-side: role assignment bound to verified Employee or bootstrap admin identity. Admin and finance roles must be audited. P1/P2 use tightly controlled bootstrap admin for management flows before P4 role-based access is implemented. At P4, complete both credentials plus role authorization, and retire unsafe assumptions of a trusted employeeId.

## Secrets and abuse defense

Provider credentials encrypted with AES-GCM using versioned master key from environment. Do not store unencrypted Provider secrets or log messages, entire prompts, generated output or authorization headers. Provider endpoints use HTTPS by default and per-Provider allowlisted host/port, redirect rejection and DNS/IP range validation to prevent SSRF; local mock exceptions only in test configuration. Request payload size, number of messages, max_tokens, per-Key rate, active SSE sessions and total upstream connect/read/write deadlines are bounded. Admin rejection audits contain actorId, action, targetId, timestamp and safe reason only.

## Acceptance tests

- Correct Agent Key without Employee Key => 401, zero upstream calls.
- Correct Employee Key with wrong Agent key => 401, zero upstream calls.
- Employee uses unauthorized Agent or logical Model => 403, zero upstream calls.
- Revoked key, expired key, disabled Employee/Agent => denied before egress.
- Agent Key attempts /api/v1 admin mutations => denied.
- TeamManager approves a grant from an unrelated Team => denied.
- Env master key missing in non-test deployment => fails startup safely.
- Raw credentials and Prompt are absent from application logs/DB audit and FinOps events.
