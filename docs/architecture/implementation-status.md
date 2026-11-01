# Implementation status

## Slice 0: architecture and contract

- [x] Choose shared ParcelFlow product domain and stack.
- [x] Define banking/payment scenarios and AI safety principles.
- [x] Add initial versioned OpenAPI contract.
- [x] Establish monorepo directories and Java service entry point.

## Slice 1: banking transfer vertical slice (implemented in source, verification pending)

- [x] Add PostgreSQL schema and Flyway seed fixtures for synthetic multi-currency customer accounts.
- [x] Implement account list/detail reads with ledger, available, pending, version fields, and opaque cursor pagination.
- [x] Implement internal double-entry transfers with deterministic account lock ordering, currency/status/funds checks, a per-transfer cap, nested metadata persistence, and idempotency-key replay protection.
- [x] Return structured problem details with error codes and trace IDs.
- [x] Add RestAssured scenarios against disposable PostgreSQL using Testcontainers.
- [x] Add Docker Compose local PostgreSQL and API CI workflow.
- [x] Implement transaction history with status/date filters and opaque stable cursor pagination.
- [ ] Run Maven verify and address any failures.

## Slice 2: identity and access (implemented in source, verification pending)

- [x] Configure Spring Security as an OAuth 2.0 Resource Server with OIDC issuer, JWKS, timestamp, and audience validation.
- [x] Map issuer realm roles to application roles and derive account ownership only from the verified token.
- [x] Protect account, transaction, and transfer routes; keep only health probes public.
- [x] Add a local Keycloak realm, PKCE public client, synthetic roles/users, and 401/403 integration scenarios.
- [ ] Verify local Keycloak discovery, token audiences, and the full CI workflow.

## Slice 3: synthetic payment lifecycle (implemented in source, verification pending)

- [x] Add versioned authorization, operation, and idempotency schema with ledger linkage.
- [x] Simulate approved, declined, and pending tokenized payment outcomes without storing raw payment credentials.
- [x] Implement manual/automatic capture, partial capture, void, refund, ownership checks, and per-operation idempotency.
- [x] Extend the OpenAPI contract and RestAssured scenarios for payment lifecycle and boundary outcomes.
- [x] Add an operations-only ledger reconciliation report with per-account variance and no-write behavior.
- [ ] Run Maven verify and address any failures.

## Slice 4: operations ledger reconciliation (implemented in source, verification pending)

- [x] Persist an opening ledger baseline, including safe backfill from prior posted activity.
- [x] Reconcile current account ledger balances against posted transfer, capture, and refund entries.
- [x] Protect the read-only report with the operations role and include mismatch detection scenarios.
- [ ] Run Maven verify and address any failures.

## Slice 5: signed payment webhook inbox (implemented in source, verification pending)

- [x] Verify HMAC signatures over the timestamp and exact raw request body, with a bounded clock-skew window.
- [x] Persist event IDs and payload hashes, count duplicate deliveries, and reject conflicting event-ID reuse.
- [x] Allow safe redelivery to resume an event while its referenced payment operation is not yet available.
- [x] Extend the OpenAPI contract and RestAssured scenarios for signatures, stale events, retries, and deduplication.
- [ ] Run Maven verify and address any failures.

## Next slices

1. Add reversals and reconciliation behavior.
2. Add ledger reversals and reconciliation exception workflows.
3. Add broader RestAssured contract, boundary, and fault-injection coverage.
4. Build web and mobile journeys, then Detox baseline and Mobilewright migration suite.
5. Add AI-assisted test-change workflow with benchmark, review gate, and failure/bottleneck triage.
6. Finish CI reports, deployment workflow, and GitHub Pages case study.

## Release decisions still needed

- Hosting provider for the interactive API/web demo (GitHub Pages only hosts static content).
- Whether iOS CI coverage is required; that needs a macOS runner.
