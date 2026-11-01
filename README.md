# ParcelFlow QA Engineering Portfolio

A portfolio project demonstrating production-minded quality engineering across web, mobile, banking, payments, and APIs. It uses synthetic data and simulated financial rails only; it does not connect to real institutions or process funds.

## Current state

The API includes PostgreSQL-backed synthetic accounts, cursor-paginated account and transaction reads, double-entry internal transfers, idempotency protection, structured errors, role-based JWT authorization, local OIDC configuration, RestAssured integration scenarios, and an API CI workflow. Payment lifecycle, clients, AI-assisted workflows, and end-to-end verification are follow-up slices.

## Planned stack

- Web: React, TypeScript, Playwright
- Mobile: React Native, TypeScript, Detox baseline and expanded Mobilewright suite
- API: Java 21, Spring Boot, PostgreSQL; RestAssured API automation
- Identity: OIDC, Keycloak for local development, Spring Security resource server
- Local services: Docker Compose
- CI and portfolio: GitHub Actions and GitHub Pages

## Local API setup

Copy `.env.example` to `.env`, set a local-only Keycloak admin password, and start services:

```powershell
Copy-Item .env.example .env
docker compose --env-file .env -f infra/compose/docker-compose.yml up -d
```

Then follow [the API setup guide](services/api/README.md). Demo users and all financial records are synthetic. The local Keycloak dev image and credentials are not for production.

## Product scenarios

Synthetic customer accounts, balances and transaction history; internal transfers; account funding for checkout; tokenized payment instruments; payment authorization/capture/void/refund; order and fulfilment journeys; idempotency, pending/posted transitions, reversals, reconciliation, and audit history.

## AI safety model

AI may propose test changes and analyze test evidence. Generated tests remain reviewed code; CI can run without an LLM; AI cannot silently remove assertions, convert failures to passes, or quarantine tests without an auditable reason.

## License

TBD before public release.
