# ParcelFlow QA Engineering Portfolio

A portfolio project demonstrating production-minded quality engineering across web, mobile, banking, payments, and APIs. It uses synthetic data and simulated financial rails only; it does not connect to real institutions or process funds.

## Current state

The first executable API slice is in place: synthetic account list and detail endpoints, an OpenAPI contract, Spring Boot service shell, and Actuator health endpoint. The account fixtures are in memory. Authentication, persistence, transfer writes, payment lifecycle, clients, automation, and CI are follow-up slices.

## Planned stack

- Web: React, TypeScript, Playwright
- Mobile: React Native, TypeScript, Detox baseline and expanded Mobilewright suite
- API: Java 21, Spring Boot, PostgreSQL; RestAssured API automation
- Local services: Docker Compose
- CI and portfolio: GitHub Actions and GitHub Pages

## Run the API

With JDK 21+ and Maven installed:

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
