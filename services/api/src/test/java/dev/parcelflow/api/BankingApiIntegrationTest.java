package dev.parcelflow.api;

import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(BankingApiIntegrationTest.TestJwtDecoderConfiguration.class)
class BankingApiIntegrationTest {
    private static final String CHECKING = "10000000-0000-4000-8000-000000000001";
    private static final String SAVINGS = "10000000-0000-4000-8000-000000000002";
    private static final String EURO = "10000000-0000-4000-8000-000000000003";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void resetDatabase() {
        RestAssured.port = port;
        RestAssured.registerParser("application/problem+json", io.restassured.parsing.Parser.JSON);
        jdbc.update("DELETE FROM account_transactions");
        jdbc.update("DELETE FROM payment_idempotency");
        jdbc.update("DELETE FROM payment_operations");
        jdbc.update("DELETE FROM payment_authorizations");
        jdbc.update("DELETE FROM transfer_idempotency");
        jdbc.update("DELETE FROM transfers");
        jdbc.update("UPDATE customer_accounts SET ledger_balance = 2840.50, pending_debit = 225.00, version = 7 WHERE id = ?", java.util.UUID.fromString(CHECKING));
        jdbc.update("UPDATE customer_accounts SET ledger_balance = 12500.00, pending_debit = 0.00, version = 3 WHERE id = ?", java.util.UUID.fromString(SAVINGS));
        jdbc.update("UPDATE customer_accounts SET ledger_balance = 750.00, pending_debit = 0.00, version = 1 WHERE id = ?", java.util.UUID.fromString(EURO));
    }

    private io.restassured.specification.RequestSpecification customer() {
        return given().header("Authorization", "Bearer test-customer-token");
    }

    @Test
    void requiresAValidTokenAndCustomerRole() {
        given().get("/api/v1/accounts").then().statusCode(401).body("code", equalTo("UNAUTHENTICATED"));
        given().header("Authorization", "Bearer invalid-test-token")
                .get("/api/v1/accounts").then().statusCode(401).body("code", equalTo("UNAUTHENTICATED"));
        given().header("Authorization", "Bearer test-operations-token")
                .get("/api/v1/accounts").then().statusCode(403).body("code", equalTo("FORBIDDEN"));
        customer().get("/api/v1/accounts").then().statusCode(200);
    }

    @Test
    void accountReconciliationIsOperationsOnlyAndDetectsLedgerVariance() {
        customer().get("/api/v1/operations/reconciliation/accounts").then().statusCode(403);
        given().header("Authorization", "Bearer test-operations-token")
                .get("/api/v1/operations/reconciliation/accounts").then().statusCode(200)
                .body("status", equalTo("COMPLETED"))
                .body("accountCount", equalTo(3)).body("mismatchCount", equalTo(0));

        jdbc.update("UPDATE customer_accounts SET ledger_balance = ledger_balance + 1.00 WHERE id = ?",
                java.util.UUID.fromString(CHECKING));
        given().header("Authorization", "Bearer test-operations-token")
                .get("/api/v1/operations/reconciliation/accounts").then().statusCode(200)
                .body("mismatchCount", equalTo(1))
                .body("accounts.find { it.accountId == '" + CHECKING + "' }.variance.amount", equalTo("1.00"))
                .body("accounts.find { it.accountId == '" + CHECKING + "' }.status", equalTo("MISMATCH"));
    }

    @Test
    void accountReadsExposeLedgerAvailableAndCurrency() {
        customer()
                .when().get("/api/v1/accounts")
                .then().statusCode(200)
                .body("items.size()", equalTo(3))
                .body("items[0].balance.ledger.amount", notNullValue())
                .body("page.hasMore", equalTo(false));

        var nextCursor = customer().queryParam("limit", 1).get("/api/v1/accounts")
                .then().statusCode(200).body("items.size()", equalTo(1))
                .body("page.hasMore", equalTo(true)).extract().path("page.nextCursor");
        customer().queryParam("limit", 1).queryParam("cursor", nextCursor).get("/api/v1/accounts")
                .then().statusCode(200).body("items.size()", equalTo(1))
                .body("items[0].id", equalTo(SAVINGS));

        customer()
                .when().get("/api/v1/accounts/{id}", CHECKING)
                .then().statusCode(200)
                .body("balance.ledger.amount", equalTo("2840.50"))
                .body("balance.available.amount", equalTo("2615.50"))
                .body("balance.pendingDebit.amount", equalTo("225.00"));
    }

    @Test
    void transferPostsBalancedEntriesAndUpdatesBothBalances() {
        customer().header("Idempotency-Key", "transfer-success-001")
                .contentType("application/json")
                .body("""
                        {"sourceAccountId":"%s","destinationAccountId":"%s","amount":{"amount":"100.25","currency":"USD"},"memo":"QA demo transfer","metadata":{"channel":"mobile","journeyId":"checkout-01"}}
                        """.formatted(CHECKING, SAVINGS))
                .when().post("/api/v1/transfers")
                .then().statusCode(201)
                .body("status", equalTo("POSTED"))
                .body("amount.amount", equalTo("100.25"))
                .body("sourceTransactionId", notNullValue())
                .body("destinationTransactionId", notNullValue());

        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT metadata ->> 'channel' FROM transfers ORDER BY created_at DESC LIMIT 1", String.class))
                .isEqualTo("mobile");

        customer().when().get("/api/v1/accounts/{id}", CHECKING)
                .then().statusCode(200).body("balance.ledger.amount", equalTo("2740.25"));
        customer().when().get("/api/v1/accounts/{id}", SAVINGS)
                .then().statusCode(200).body("balance.ledger.amount", equalTo("12600.25"));
    }

    @Test
    void idempotencyReplayReturnsSameTransferWithoutDoublePosting() {
        var request = customer().header("Idempotency-Key", "transfer-replay-001")
                .contentType("application/json").body(transferBody(CHECKING, SAVINGS, "50.00", "USD"));
        var first = request.when().post("/api/v1/transfers").then().statusCode(201).extract().path("id");
        var replay = customer().header("Idempotency-Key", "transfer-replay-001")
                .contentType("application/json").body(transferBody(CHECKING, SAVINGS, "50.00", "USD"))
                .when().post("/api/v1/transfers").then().statusCode(201).extract().path("id");

        org.assertj.core.api.Assertions.assertThat(replay).isEqualTo(first);
        customer().when().get("/api/v1/accounts/{id}", CHECKING)
                .then().statusCode(200).body("balance.ledger.amount", equalTo("2790.50"));
    }

    @Test
    void rejectsIdempotencyKeyReuseWithDifferentPayload() {
        customer().header("Idempotency-Key", "transfer-mismatch-01").contentType("application/json")
                .body(transferBody(CHECKING, SAVINGS, "25.00", "USD"))
                .when().post("/api/v1/transfers").then().statusCode(201);

        customer().header("Idempotency-Key", "transfer-mismatch-01").contentType("application/json")
                .body(transferBody(CHECKING, SAVINGS, "26.00", "USD"))
                .when().post("/api/v1/transfers").then().statusCode(409)
                .body("code", equalTo("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void rejectsInsufficientFundsSameAccountAndCurrencyMismatch() {
        customer().header("Idempotency-Key", "transfer-insufficient-01").contentType("application/json")
                .body(transferBody(CHECKING, SAVINGS, "3000.00", "USD"))
                .when().post("/api/v1/transfers").then().statusCode(422)
                .body("code", equalTo("INSUFFICIENT_FUNDS"));

        customer().header("Idempotency-Key", "transfer-same-account-01").contentType("application/json")
                .body(transferBody(CHECKING, CHECKING, "1.00", "USD"))
                .when().post("/api/v1/transfers").then().statusCode(422)
                .body("code", equalTo("SAME_ACCOUNT"));

        customer().header("Idempotency-Key", "transfer-currency-001").contentType("application/json")
                .body(transferBody(CHECKING, EURO, "1.00", "USD"))
                .when().post("/api/v1/transfers").then().statusCode(422)
                .body("code", equalTo("CURRENCY_MISMATCH"));
    }

    @Test
    void concurrentTransfersCannotSpendTheSameAvailableFundsTwice() {
        var first = java.util.concurrent.CompletableFuture.supplyAsync(() -> customer()
                .header("Idempotency-Key", "transfer-concurrent-a")
                .contentType("application/json")
                .body(transferBody(CHECKING, SAVINGS, "2000.00", "USD"))
                .post("/api/v1/transfers").statusCode());
        var second = java.util.concurrent.CompletableFuture.supplyAsync(() -> customer()
                .header("Idempotency-Key", "transfer-concurrent-b")
                .contentType("application/json")
                .body(transferBody(CHECKING, SAVINGS, "2000.00", "USD"))
                .post("/api/v1/transfers").statusCode());

        var statuses = java.util.List.of(first.join(), second.join());
        org.assertj.core.api.Assertions.assertThat(statuses).containsExactlyInAnyOrder(201, 422);
        customer().when().get("/api/v1/accounts/{id}", CHECKING)
                .then().statusCode(200).body("balance.ledger.amount", equalTo("840.50"));
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transfers", Long.class))
                .isEqualTo(1L);
    }

    @Test
    void transactionHistorySupportsStatusFilterAndStableCursorPagination() {
        customer().header("Idempotency-Key", "history-transfer-001").contentType("application/json")
                .body(transferBody(CHECKING, SAVINGS, "15.00", "USD"))
                .post("/api/v1/transfers").then().statusCode(201);
        customer().header("Idempotency-Key", "history-transfer-002").contentType("application/json")
                .body(transferBody(CHECKING, SAVINGS, "20.00", "USD"))
                .post("/api/v1/transfers").then().statusCode(201);

        var pageOne = customer().queryParam("limit", 1).get("/api/v1/accounts/{id}/transactions", CHECKING)
                .then().statusCode(200).body("items.size()", equalTo(1))
                .body("items[0].type", equalTo("TRANSFER_DEBIT"))
                .body("items[0].status", equalTo("POSTED"))
                .body("page.hasMore", equalTo(true));
        var cursor = pageOne.extract().path("page.nextCursor");
        var firstId = pageOne.extract().path("items[0].id");
        var secondId = customer().queryParam("limit", 1).queryParam("cursor", cursor)
                .get("/api/v1/accounts/{id}/transactions", CHECKING)
                .then().statusCode(200).body("items.size()", equalTo(1))
                .body("page.hasMore", equalTo(false)).extract().path("items[0].id");

        org.assertj.core.api.Assertions.assertThat(secondId).isNotEqualTo(firstId);
        customer().queryParam("status", "POSTED").get("/api/v1/accounts/{id}/transactions", CHECKING)
                .then().statusCode(200).body("items.size()", equalTo(2));
    }

    @Test
    void paymentAuthorizationCanBePartiallyCapturedAndRefundedIdempotently() {
        var authorizationId = customer().header("Idempotency-Key", "payment-auth-001")
                .contentType("application/json").body(authorizationBody("100.00", "tok_demo_approved_001", "MANUAL"))
                .post("/api/v1/payments/authorizations").then().statusCode(201)
                .body("status", equalTo("AUTHORIZED"))
                .body("capturedAmount.amount", equalTo("0.00"))
                .extract().path("id");
        customer().get("/api/v1/accounts/{id}", CHECKING).then().statusCode(200)
                .body("balance.available.amount", equalTo("2515.50"))
                .body("balance.pendingDebit.amount", equalTo("325.00"));

        var firstCapture = customer().header("Idempotency-Key", "payment-cap-001").contentType("application/json")
                .body("{\"amount\":{\"amount\":\"40.00\",\"currency\":\"USD\"}}")
                .post("/api/v1/payments/authorizations/{id}/capture", authorizationId)
                .then().statusCode(200).body("type", equalTo("CAPTURE"))
                .body("amount.amount", equalTo("40.00")).extract().path("id");
        var replayedCapture = customer().header("Idempotency-Key", "payment-cap-001").contentType("application/json")
                .body("{\"amount\":{\"amount\":\"40.00\",\"currency\":\"USD\"}}")
                .post("/api/v1/payments/authorizations/{id}/capture", authorizationId)
                .then().statusCode(200).extract().path("id");
        org.assertj.core.api.Assertions.assertThat(replayedCapture).isEqualTo(firstCapture);

        customer().header("Idempotency-Key", "payment-cap-002").post("/api/v1/payments/authorizations/{id}/capture", authorizationId)
                .then().statusCode(200).body("amount.amount", equalTo("60.00"));
        customer().get("/api/v1/payments/authorizations/{id}", authorizationId).then().statusCode(200)
                .body("status", equalTo("CAPTURED")).body("capturedAmount.amount", equalTo("100.00"));
        customer().header("Idempotency-Key", "payment-refund-001").contentType("application/json")
                .body("{\"amount\":{\"amount\":\"25.00\",\"currency\":\"USD\"}}")
                .post("/api/v1/payments/authorizations/{id}/refunds", authorizationId)
                .then().statusCode(201).body("type", equalTo("REFUND"));
        customer().get("/api/v1/accounts/{id}", CHECKING).then().statusCode(200)
                .body("balance.ledger.amount", equalTo("2765.50"))
                .body("balance.pendingDebit.amount", equalTo("225.00"));
        customer().queryParam("limit", 10).get("/api/v1/accounts/{id}/transactions", CHECKING)
                .then().statusCode(200).body("items.findAll { it.type == 'PAYMENT_CAPTURE' }.size()", equalTo(2))
                .body("items.findAll { it.type == 'PAYMENT_REFUND' }.size()", equalTo(1));
        given().header("Authorization", "Bearer test-operations-token")
                .get("/api/v1/operations/reconciliation/accounts").then().statusCode(200)
                .body("mismatchCount", equalTo(0));
    }

    @Test
    void paymentDeclinesAndPendingAuthorizationsHaveDeterministicOutcomes() {
        customer().header("Idempotency-Key", "payment-decline-001").contentType("application/json")
                .body(authorizationBody("20.00", "tok_demo_decline_dnh_001", "MANUAL"))
                .post("/api/v1/payments/authorizations").then().statusCode(201)
                .body("status", equalTo("DECLINED")).body("declineCode", equalTo("DO_NOT_HONOR"));
        customer().header("Idempotency-Key", "payment-funds-001").contentType("application/json")
                .body(authorizationBody("3000.00", "tok_demo_approved_001", "MANUAL"))
                .post("/api/v1/payments/authorizations").then().statusCode(201)
                .body("status", equalTo("DECLINED")).body("declineCode", equalTo("INSUFFICIENT_FUNDS"));
        var pendingId = customer().header("Idempotency-Key", "payment-pending-001").contentType("application/json")
                .body(authorizationBody("50.00", "tok_demo_timeout_001", "MANUAL"))
                .post("/api/v1/payments/authorizations").then().statusCode(201)
                .body("status", equalTo("PENDING")).extract().path("id");
        customer().header("Idempotency-Key", "payment-void-001")
                .post("/api/v1/payments/authorizations/{id}/void", pendingId).then()
                .statusCode(200).body("type", equalTo("VOID"));
        customer().get("/api/v1/payments/authorizations/{id}", pendingId).then().statusCode(200)
                .body("status", equalTo("VOIDED"));
        customer().get("/api/v1/accounts/{id}", CHECKING).then().statusCode(200)
                .body("balance.pendingDebit.amount", equalTo("225.00"));
    }

    @Test
    void automaticCaptureAndRefundBoundariesAreEnforced() {
        var authorizationId = customer().header("Idempotency-Key", "payment-auto-001").contentType("application/json")
                .body(authorizationBody("20.00", "tok_demo_approved_001", "AUTOMATIC"))
                .post("/api/v1/payments/authorizations").then().statusCode(201)
                .body("status", equalTo("CAPTURED")).body("capturedAmount.amount", equalTo("20.00"))
                .extract().path("id");
        customer().header("Idempotency-Key", "payment-refund-limit")
                .contentType("application/json").body("{\"amount\":{\"amount\":\"20.01\",\"currency\":\"USD\"}}")
                .post("/api/v1/payments/authorizations/{id}/refunds", authorizationId).then()
                .statusCode(422).body("code", equalTo("REFUND_EXCEEDS_CAPTURE"));
        customer().header("Idempotency-Key", "payment-refund-valid")
                .contentType("application/json").body("{\"amount\":{\"amount\":\"20.00\",\"currency\":\"USD\"}}")
                .post("/api/v1/payments/authorizations/{id}/refunds", authorizationId).then().statusCode(201);
        customer().get("/api/v1/accounts/{id}", CHECKING).then().statusCode(200)
                .body("balance.ledger.amount", equalTo("2840.50"));
    }

    private String authorizationBody(String amount, String token, String captureMode) {
        return """
                {"accountId":"%s","paymentToken":"%s","amount":{"amount":"%s","currency":"USD"},"merchantReference":"checkout-demo-001","captureMode":"%s","metadata":{"channel":"mobile","journey":"checkout"}}
                """.formatted(CHECKING, token, amount, captureMode);
    }

    private String transferBody(String source, String destination, String amount, String currency) {
        return """
                {"sourceAccountId":"%s","destinationAccountId":"%s","amount":{"amount":"%s","currency":"%s"},"memo":"QA demo transfer"}
                """.formatted(source, destination, amount, currency);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestJwtDecoderConfiguration {
        @Bean
        @Primary
        JwtDecoder testJwtDecoder() {
            return token -> {
                boolean operations = "test-operations-token".equals(token);
                if (!operations && !"test-customer-token".equals(token)) {
                    throw new BadJwtException("Unrecognized integration-test token");
                }
                var now = Instant.now();
                return Jwt.withTokenValue(token)
                        .header("alg", "RS256")
                        .issuer("http://localhost:8090/realms/parcelflow")
                        .audience(List.of("parcelflow-api"))
                        .subject(operations ? "demo-operations" : "demo-customer-1")
                        .claim("preferred_username", operations ? "demo-operations" : "demo-customer-1")
                        .claim("realm_access", Map.of("roles", List.of(operations ? "operations" : "customer")))
                        .issuedAt(now.minusSeconds(5))
                        .expiresAt(now.plusSeconds(300))
                        .build();
            };
        }
    }
}
