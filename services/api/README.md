# Service API

A Java 21 / Spring Boot API backed by PostgreSQL and Flyway. All account, payment, and identity data is synthetic.

## Run locally

Copy `.env.example` to `.env`, change the local Keycloak admin password, then start PostgreSQL and Keycloak from the repository root:

```powershell
Copy-Item .env.example .env
# Edit .env and set a local-only password
docker compose --env-file .env -f infra/compose/docker-compose.yml up -d
```

Start the API from `services/api`:

```powershell
mvn spring-boot:run
```

Keycloak is configured as a local development identity provider. Obtain a customer token using OAuth 2.0 Authorization Code with PKCE (for example, in Postman):

- Issuer: `http://localhost:8090/realms/parcelflow`
- Authorization endpoint: `http://localhost:8090/realms/parcelflow/protocol/openid-connect/auth`
- Token endpoint: `http://localhost:8090/realms/parcelflow/protocol/openid-connect/token`
- Client ID: `parcelflow-web` (public client; no client secret)
- Scope: `openid profile`
- Synthetic customer: `demo-customer-1` / `demo-customer-pass`
- Synthetic operations user: `demo-operations` / `demo-operations-pass`

Explore the health endpoint and bearer-protected API:

- `GET http://localhost:8080/actuator/health`
- `GET http://localhost:8080/api/v1/accounts`
- `GET http://localhost:8080/api/v1/accounts/10000000-0000-4000-8000-000000000001`
- `GET http://localhost:8080/api/v1/accounts/10000000-0000-4000-8000-000000000001/transactions`
- `POST http://localhost:8080/api/v1/transfers` with an `Idempotency-Key` header
- `POST http://localhost:8080/api/v1/payments/authorizations` with an `Idempotency-Key` header
- `GET http://localhost:8080/api/v1/operations/reconciliation/accounts` with an operations-role token

The API validates JWT signatures, issuer, timestamps, and audience through Spring Security OAuth2 Resource Server. Realm roles become `ROLE_CUSTOMER` / `ROLE_OPERATIONS`; account ownership comes from the verified token's `preferred_username` claim. Customer endpoints require the customer role. Integration tests use a test-only fixed decoder and synthetic tokens, not a live identity provider.

The bundled Keycloak uses `start-dev` and public synthetic users for local development only. Never use its development mode, demo passwords, or this service with real financial data. A deployed environment must use a separately managed production OIDC provider and secret/realm configuration.

## Integration tests

The RestAssured suite uses Testcontainers to run against disposable PostgreSQL and covers authentication/authorization, balances and pagination, double-entry transfers, concurrent spend protection, transfer idempotency, payment holds, partial and automatic capture, voids, refunds, and payment idempotency. Docker must be available for `mvn verify`.

### Synthetic payment simulator

Payment requests accept simulator tokens only; the API never accepts PAN, CVV, or real provider credentials. Use these fixed fixtures for deterministic journey outcomes:

| Token | Simulated outcome |
| --- | --- |
| `tok_demo_approved_001` | Approved; remains an authorization in `MANUAL` mode or captures immediately in `AUTOMATIC` mode |
| `tok_demo_decline_funds_01` | Declined with `INSUFFICIENT_FUNDS` |
| `tok_demo_decline_dnh_001` | Declined with `DO_NOT_HONOR` |
| `tok_demo_timeout_001` | Pending authorization; can be voided to release its hold |

Every authorize, capture, void, and refund request requires an `Idempotency-Key`. Authorization holds reduce available balance; capture moves the captured amount from pending debit to the ledger; void releases the uncaptured remainder; refunds credit captured amounts back to the synthetic account. These are local simulation semantics, not a connection to a payment processor.

The operations reconciliation endpoint compares each current ledger balance with its persisted opening balance plus posted transfer, capture, and refund entries. It reports per-account variance and does not mutate balances. It requires the `operations` realm role; a customer-role token receives `403`.
