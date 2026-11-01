# Service API

A Java 21 / Spring Boot API backed by PostgreSQL and Flyway. All fixtures and financial operations are synthetic.

## Run locally

Start PostgreSQL from the repository root:

```powershell
docker compose -f infra/compose/docker-compose.yml up -d
```

Start the service from `services/api`:

```powershell
mvn spring-boot:run
```

Explore:

- `GET http://localhost:8080/actuator/health`
- `GET http://localhost:8080/api/v1/accounts`
- `GET http://localhost:8080/api/v1/accounts/10000000-0000-4000-8000-000000000001`
- `GET http://localhost:8080/api/v1/accounts/10000000-0000-4000-8000-000000000001/transactions`
- `POST http://localhost:8080/api/v1/transfers` with an `Idempotency-Key` header

The demo currently uses a fixed synthetic customer identity. Authentication/authorization is a next implementation slice; this is not suitable for real financial data or deployment as a real banking service.

## Integration tests

The RestAssured suite uses Testcontainers to run against a disposable PostgreSQL instance. Docker must be available for `mvn verify`.
