# Implementation status

## Slice 0: architecture and contract

- [x] Choose shared ParcelFlow product domain and stack.
- [x] Define banking/payment scenarios and AI safety principles.
- [x] Add initial versioned OpenAPI contract.
- [x] Establish monorepo directories and Java service entry point.

## Slice 1: account read API (implemented, not yet verified)

- [x] Add synthetic in-memory account fixtures and typed account/balance models.
- [x] Implement account list and detail endpoints with limit validation and not-found behavior.
- [x] Configure Actuator health/metrics endpoints.
- [ ] Align error body format with the OpenAPI Problem schema.
- [ ] Add RestAssured coverage and CI execution.

## Next slices

1. Add authentication and authorization, then PostgreSQL persistence and versioned ledger behavior.
2. Implement internal transfers with balance, limit, concurrency, and idempotency rules.
3. Add payment lifecycle and webhook/reconciliation simulator.
4. Add RestAssured tests and seeded failure cases.
5. Build web and mobile journeys, then Detox baseline and Mobilewright migration suite.
6. Add CI reports, AI-assisted test-change workflow, and GitHub Pages case study.

## Decisions deferred until release setup

- GitHub account/organization and repository name.
- Hosting provider for the interactive API/web demo (GitHub Pages only hosts static content).
- Whether iOS CI coverage is required; that requires a macOS runner.
