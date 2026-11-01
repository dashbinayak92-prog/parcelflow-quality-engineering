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
- [ ] Align the full contract with all current error/transfer responses and add account transaction history endpoint.
- [ ] Run Maven verify and address any failures.

## Next slices

1. Add authentication and authorization; remove fixed demo identity from request paths.
2. Add transaction history, reversals, and reconciliation behavior.
3. Implement payment authorization, capture, void, refund, webhook retries, and reconciliation.
4. Add broader RestAssured contract, boundary, concurrency, and fault-injection coverage.
5. Build web and mobile journeys, then Detox baseline and Mobilewright migration suite.
6. Add AI-assisted test-change workflow with benchmark, review gate, and failure/bottleneck triage.
7. Finish CI reports, deployment workflow, and GitHub Pages case study.

## Release decisions still needed

- Hosting provider for the interactive API/web demo (GitHub Pages only hosts static content).
- Whether iOS CI coverage is required; that needs a macOS runner.
