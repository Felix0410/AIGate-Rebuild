# 07A · Detailed Runtime DTO and Event Contract

## POST /v1/chat/completions

Required headers:
- Authorization: Bearer [managed application secret]
- X-Employee-Key: [separate employee secret]
- Idempotency-Key: [caller-generated unique request identifier]

The runtime caller does not choose the Employee identity. It is derived only from a verified credential and paired with the verified Agent.

Request schema (minimum): model:string; messages: nonempty array of {role:"system"|"user"|"assistant",content:string}; max_tokens:positive integer; stream:boolean. Optional: stream_options.include_usage=true. Reject unknown tool execution features in the first Provider adapter version. No arbitrary URL field is accepted.

Request constraints: body/max messages/content length and max_tokens must be bounded by configuration for the selected Model; reject invalid body before any paid outbound call. No implicit fallback to a forbidden logical Model.

Nonstream result: id, object, created, model (the actual selected model; include requested logical Model separately when corporate fallback was applied), choices, usage including verified categories, and a response trace header. If upstream usage is unreliable, clearly mark the internal Invocation as requiring reconciliation rather than invent usage.

SSE result: Content-Type text/event-stream. Forward compatible data frames in correct order; send completion terminal only for a normal stream completion, with usage included if available and trustworthy. On disconnect, attempt upstream cancellation. Streamed output and final billing are separate: once a chunk was streamed, do not pretend the call was necessarily free.

## API error

JSON keys: code:string; message:string; traceId:string; details:optional safe structured object. Authentication 401, authorization/policy 403, capacity/allowance 402 or 429, request conflict 409, bad input 400, upstream failure 503/504. Never expose opaque keys, raw Provider responses or original prompts in an error.

## Idempotency

The scope is (verified agentId, verified employeeId, idempotencyKey). Persist a payload digest and invocation status. Same key/different digest → 409. Same key while upstream may have been contacted → return status, never send another paid Provider request. For completed SSE, do not claim to replay the old chunk stream unless a separately approved encrypted response retention design exists (none is approved).

## Events

InvocationSettledV1: eventId, invocationId, employeeId, primaryTeamSnapshotId, agentId, modelId, deploymentId, confirmed usage categories, internal points, provider estimated charge/currency, pointsPriceVersionId, providerPriceVersionId, occurredAt. No raw prompt or output.

TeamGrantIssuedV1: eventId, grantId, issuerTeamId, recipientEmployeeId, issuedPoints, expiry, occurredAt. The grant event is internal allocation expenditure, NOT Provider charge. Consumers are idempotent by eventId and version-aware.

## Management DTO rule

All create/update endpoints reject negative quotas, invalid expiration, unknown Entity IDs and unauthorized role scope. List APIs use page/size/total. Sensitive secrets can appear only in the once-only credential creation response. Price versions and safety policy revisions are append-only, never edited in place.
