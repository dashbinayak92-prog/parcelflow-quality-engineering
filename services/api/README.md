# Service API

First executable slice: deterministic synthetic account listing and account detail reads.

Run with JDK 21 and Maven:

```powershell
cd services/api
mvn spring-boot:run
```

Then inspect `GET http://localhost:8080/api/v1/accounts` or `GET http://localhost:8080/actuator/health`.

Account fixtures are intentionally synthetic and currently in memory. Persistence, authentication, transaction writes, and payment operations are planned follow-up slices.
