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

## Next slices

1. Add reversals and reconciliation behavior.
2. Implement payment authorization, capture, void, refund, webhook retries, and reconciliation.
3. Add broader RestAssured contract, boundary, and fault-injection coverage.
4. Build web and mobile journeys, then Detox baseline and Mobilewright migration suite.
5. Add AI-assisted test-change workflow with benchmark, review gate, and failure/bottleneck triage.
6. Finish CI reports, deployment workflow, and GitHub Pages case study.

## Release decisions still needed

- Hosting provider for the interactive API/web demo (GitHub Pages only hosts static content).
- Whether iOS CI coverage is required; that needs a macOS runner.
