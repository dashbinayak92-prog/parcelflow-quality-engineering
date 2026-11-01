# ParcelFlow QA Engineering Portfolio

A portfolio project demonstrating production-minded quality engineering across web, mobile, banking, payments, and APIs. It uses synthetic data and simulated financial rails only; it does not connect to real institutions or process funds.

## Current state

The first banking vertical slice is in place in source: PostgreSQL-backed synthetic accounts, account reads, double-entry internal transfers, idempotency, structured errors, RestAssured integration scenarios, and an API CI workflow. Authentication, payment lifecycle, clients, AI-assisted workflows, and end-to-end verification are follow-up slices.

## Planned stack

- Web: React, TypeScript, Playwright
- Mobile: React Native, TypeScript, Detox baseline and expanded Mobilewright suite
- API: Java 21, Spring Boot, PostgreSQL; RestAssured API automation
- Local services: Docker Compose
- CI and portfolio: GitHub Actions and GitHub Pages

## Run the API

With JDK 21+, Maven, and Docker Compose installed, start PostgreSQL from the repository root with `docker compose -f infra/compose/docker-compose.yml up -d`, then run the API:

```powershell
cd services/api
mvn spring-boot:run
```

Explore `GET http://localhost:8080/api/v1/accounts`, `GET http://localhost:8080/api/v1/accounts/10000000-0000-4000-8000-000000000001`, and `GET http://localhost:8080/actuator/health`.

## Product scenarios

Synthetic customer accounts, balances and transaction history; internal transfers; account funding for checkout; tokenized payment instruments; payment authorization/capture/void/refund; order and fulfilment journeys; idempotency, pending/posted transitions, reversals, reconciliation, and audit history.

## Safety model

Never add real credentials, account numbers, card numbers, CVV, or personal information. AI assistance may propose changes, but generated tests remain reviewable code and cannot silently weaken assertions or turn failures into passes.

## License

TBD before public release.
