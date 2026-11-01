package dev.parcelflow.api.payments;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.parcelflow.api.errors.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static dev.parcelflow.api.payments.PaymentDtos.*;

@Service
public class PaymentService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public PaymentService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Authorization authorize(String ownerId, String key, AuthorizationRequest request) {
        var tokenBehavior = tokenBehaviorResult(request.paymentToken());
        var tokenFingerprint = sha256(request.paymentToken());
        var metadataJson = json(request.metadata() == null ? Map.of() : new TreeMap<>(request.metadata()));
        var requestHash = sha256(String.join("|", request.accountId().toString(), tokenFingerprint,
                request.amount().amount().stripTrailingZeros().toPlainString(), request.amount().currency(),
                request.merchantReference(), request.captureMode().name(), metadataJson));
        var replay = reserve(ownerId, key, requestHash);
        if (replay != null) {
            if (!"AUTHORIZATION".equals(replay.resourceType())) throw idempotencyConflict();
            return findAuthorization(ownerId, replay.resourceId());
        }

        var account = lockAccount(ownerId, request.accountId());
        if (!"ACTIVE".equals(account.status())) {
            throw ApiException.unprocessable("ACCOUNT_NOT_ACTIVE", "Payment account must be active");
        }
        if (!account.currency().equals(request.amount().currency())) {
            throw ApiException.unprocessable("CURRENCY_MISMATCH", "Payment currency must match the account");
        }

        var amount = request.amount().amount().setScale(2);
        var status = tokenBehavior.status();
        var declineCode = tokenBehavior.declineCode();
        var available = account.ledger().subtract(account.pendingDebit());
        if (("AUTHORIZED".equals(status) || "PENDING".equals(status)) && available.compareTo(amount) < 0) {
            status = "DECLINED";
            declineCode = "INSUFFICIENT_FUNDS";
        }
        var now = OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
        var expiresAt = now.plusDays(7);
        var authorizationId = UUID.randomUUID();
        var captured = BigDecimal.ZERO.setScale(2);
        jdbc.update("""
                INSERT INTO payment_authorizations(id, owner_id, account_id, token_fingerprint, merchant_reference,
                    amount, currency, status, decline_code, capture_mode, captured_amount, refunded_amount,
                    metadata, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?::jsonb, ?, ?)
                """, authorizationId, ownerId, account.id(), tokenFingerprint, request.merchantReference(), amount,
                account.currency(), status, declineCode, request.captureMode().name(), captured, metadataJson, now, expiresAt);

        var held = "AUTHORIZED".equals(status) || "PENDING".equals(status);
        if (held) {
            jdbc.update("UPDATE customer_accounts SET pending_debit = pending_debit + ?, version = version + 1 WHERE id = ?",
                    amount, account.id());
        }
        var authTransactionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO account_transactions(id, account_id, entry_type, status, amount, currency, description,
                    created_at, posted_at, payment_authorization_id)
                VALUES (?, ?, 'PAYMENT_AUTHORIZATION', ?, ?, ?, ?, ?, NULL, ?)
                """, authTransactionId, account.id(), "DECLINED".equals(status) ? "DECLINED" : "PENDING",
                amount, account.currency(), "Payment authorization " + request.merchantReference(), now, authorizationId);
        if (held) {
            jdbc.update("UPDATE payment_idempotency SET resource_type = 'AUTHORIZATION', resource_id = ? WHERE owner_id = ? AND idempotency_key = ?",
                    authorizationId, ownerId, key);
        } else {
            jdbc.update("UPDATE payment_idempotency SET resource_type = 'AUTHORIZATION', resource_id = ? WHERE owner_id = ? AND idempotency_key = ?",
                    authorizationId, ownerId, key);
        }

        if (held && request.captureMode() == CaptureMode.AUTOMATIC) {
            var operation = applyCapture(ownerId, authorizationId, account, amount, amount, now, true);
            status = "CAPTURED";
            captured = operation.amount().amount();
        }
        return new Authorization(authorizationId, status, money(amount, account.currency()),
                money(captured, account.currency()), declineCode, now, expiresAt);
    }

    @Transactional
    public Operation capture(String ownerId, UUID authorizationId, String key, CaptureRequest request) {
        var replay = reserve(ownerId, key, sha256("CAPTURE|" + authorizationId + "|" + amountKey(request == null ? null : request.amount())));
        if (replay != null) return replayOperation(replay, "CAPTURE");
        var authorization = lockAuthorization(ownerId, authorizationId);
        if (!List.of("AUTHORIZED", "PARTIALLY_CAPTURED").contains(authorization.status())) {
            throw ApiException.unprocessable("AUTHORIZATION_NOT_CAPTURABLE", "Authorization is not available for capture");
        }
        var remaining = authorization.amount().subtract(authorization.capturedAmount());
        var amount = requestedAmount(request == null ? null : request.amount(), remaining, authorization.currency());
        if (amount.compareTo(remaining) > 0) {
            throw ApiException.unprocessable("CAPTURE_EXCEEDS_AUTHORIZATION", "Capture exceeds the uncaptured authorization amount");
        }
        var account = lockAccount(ownerId, authorization.accountId());
        var operation = applyCapture(ownerId, authorizationId, account, amount, authorization.amount(),
                OffsetDateTime.now(ZoneOffset.UTC).withNano(0), false);
        completeIdempotency(ownerId, key, "OPERATION", operation.id());
        return operation;
    }

    @Transactional
    public Operation voidAuthorization(String ownerId, UUID authorizationId, String key) {
        var replay = reserve(ownerId, key, sha256("VOID|" + authorizationId));
        if (replay != null) return replayOperation(replay, "VOID");
        var authorization = lockAuthorization(ownerId, authorizationId);
        if (!List.of("PENDING", "AUTHORIZED", "PARTIALLY_CAPTURED").contains(authorization.status())) {
            throw ApiException.unprocessable("AUTHORIZATION_NOT_VOIDABLE", "Authorization has no remaining hold to void");
        }
        var remaining = authorization.amount().subtract(authorization.capturedAmount());
        if (remaining.signum() <= 0) throw ApiException.unprocessable("AUTHORIZATION_NOT_VOIDABLE", "Authorization has no remaining hold to void");
        var account = lockAccount(ownerId, authorization.accountId());
        jdbc.update("UPDATE customer_accounts SET pending_debit = pending_debit - ?, version = version + 1 WHERE id = ?",
                remaining, account.id());
        jdbc.update("UPDATE payment_authorizations SET status = 'VOIDED' WHERE id = ?", authorizationId);
        jdbc.update("UPDATE account_transactions SET status = 'REVERSED' WHERE payment_authorization_id = ? AND entry_type = 'PAYMENT_AUTHORIZATION' AND status = 'PENDING'",
                authorizationId);
        var operation = insertOperation(ownerId, authorizationId, "VOID", remaining, authorization.currency(), OffsetDateTime.now(ZoneOffset.UTC).withNano(0));
        completeIdempotency(ownerId, key, "OPERATION", operation.id());
        return operation;
    }

    @Transactional
    public Operation refund(String ownerId, UUID authorizationId, String key, RefundRequest request) {
        var amount = request.amount().amount().setScale(2);
        var replay = reserve(ownerId, key, sha256("REFUND|" + authorizationId + "|" + amount.toPlainString() + "|" + request.amount().currency()));
        if (replay != null) return replayOperation(replay, "REFUND");
        var authorization = lockAuthorization(ownerId, authorizationId);
        if (!List.of("CAPTURED", "PARTIALLY_CAPTURED").contains(authorization.status())) {
            throw ApiException.unprocessable("AUTHORIZATION_NOT_REFUNDABLE", "Only captured payments can be refunded");
        }
        if (!authorization.currency().equals(request.amount().currency())) {
            throw ApiException.unprocessable("CURRENCY_MISMATCH", "Refund currency must match the captured payment");
        }
        if (authorization.refundedAmount().add(amount).compareTo(authorization.capturedAmount()) > 0) {
            throw ApiException.unprocessable("REFUND_EXCEEDS_CAPTURE", "Refund exceeds the unrefunded captured amount");
        }
        var account = lockAccount(ownerId, authorization.accountId());
        var now = OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
        var operation = insertOperation(ownerId, authorizationId, "REFUND", amount, authorization.currency(), now);
        jdbc.update("UPDATE customer_accounts SET ledger_balance = ledger_balance + ?, version = version + 1 WHERE id = ?",
                amount, account.id());
        jdbc.update("UPDATE payment_authorizations SET refunded_amount = refunded_amount + ? WHERE id = ?", amount, authorizationId);
        jdbc.update("""
                INSERT INTO account_transactions(id, account_id, entry_type, status, amount, currency, description,
                    created_at, posted_at, payment_authorization_id, payment_operation_id)
                VALUES (?, ?, 'PAYMENT_REFUND', 'POSTED', ?, ?, 'Payment refund', ?, ?, ?, ?)
                """, UUID.randomUUID(), account.id(), amount, authorization.currency(), now, now, authorizationId, operation.id());
        completeIdempotency(ownerId, key, "OPERATION", operation.id());
        return operation;
    }

    @Transactional(readOnly = true)
    public Authorization findAuthorization(String ownerId, UUID id) {
        var result = jdbc.query("""
                SELECT id, status, amount, captured_amount, currency, decline_code, created_at, expires_at
                FROM payment_authorizations WHERE owner_id = ? AND id = ?
                """, rs -> rs.next() ? new Authorization(rs.getObject("id", UUID.class), rs.getString("status"),
                money(rs.getBigDecimal("amount"), rs.getString("currency").trim()),
                money(rs.getBigDecimal("captured_amount"), rs.getString("currency").trim()), rs.getString("decline_code"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("expires_at", OffsetDateTime.class)) : null,
                ownerId, id);
        if (result == null) throw ApiException.notFound("AUTHORIZATION_NOT_FOUND", "Payment authorization not found");
        return result;
    }

    private Operation applyCapture(String ownerId, UUID authorizationId, AccountRow account, BigDecimal amount,
                                   BigDecimal authorizedAmount, OffsetDateTime now, boolean automatic) {
        var auth = lockAuthorization(ownerId, authorizationId);
        var captured = auth.capturedAmount().add(amount);
        var finalCapture = captured.compareTo(authorizedAmount) == 0;
        jdbc.update("UPDATE customer_accounts SET ledger_balance = ledger_balance - ?, pending_debit = pending_debit - ?, version = version + 1 WHERE id = ?",
                amount, amount, account.id());
        jdbc.update("UPDATE payment_authorizations SET captured_amount = ?, status = ? WHERE id = ?",
                captured, finalCapture ? "CAPTURED" : "PARTIALLY_CAPTURED", authorizationId);
        if (finalCapture) {
            // The authorization is a hold event; captured value is represented by PAYMENT_CAPTURE entries.
            jdbc.update("UPDATE account_transactions SET status = 'REVERSED' WHERE payment_authorization_id = ? AND entry_type = 'PAYMENT_AUTHORIZATION' AND status = 'PENDING'",
                    authorizationId);
        }
        var operation = insertOperation(ownerId, authorizationId, "CAPTURE", amount, auth.currency(), now);
        jdbc.update("""
                INSERT INTO account_transactions(id, account_id, entry_type, status, amount, currency, description,
                    created_at, posted_at, payment_authorization_id, payment_operation_id)
                VALUES (?, ?, 'PAYMENT_CAPTURE', 'POSTED', ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), account.id(), amount, auth.currency(), automatic ? "Automatic payment capture" : "Payment capture",
                now, now, authorizationId, operation.id());
        return operation;
    }

    private Operation insertOperation(String ownerId, UUID authId, String type, BigDecimal amount,
                                      String currency, OffsetDateTime now) {
        var id = UUID.randomUUID();
        jdbc.update("INSERT INTO payment_operations(id, owner_id, authorization_id, type, status, amount, currency, created_at) VALUES (?, ?, ?, ?, 'SUCCEEDED', ?, ?, ?)",
                id, ownerId, authId, type, amount, currency, now);
        return new Operation(id, authId, type, "SUCCEEDED", money(amount, currency), now);
    }

    private Operation replayOperation(Idempotency replay, String expectedType) {
        if (!"OPERATION".equals(replay.resourceType())) throw idempotencyConflict();
        var operation = jdbc.query("SELECT id, authorization_id, type, status, amount, currency, created_at FROM payment_operations WHERE owner_id = ? AND id = ?",
                rs -> rs.next() ? new Operation(rs.getObject("id", UUID.class), rs.getObject("authorization_id", UUID.class),
                        rs.getString("type"), rs.getString("status"), money(rs.getBigDecimal("amount"), rs.getString("currency").trim()),
                        rs.getObject("created_at", OffsetDateTime.class)) : null, replay.ownerId(), replay.resourceId());
        if (operation == null || !expectedType.equals(operation.type())) throw idempotencyConflict();
        return operation;
    }

    private PaymentAuthorizationRow lockAuthorization(String ownerId, UUID id) {
        var row = jdbc.query("SELECT id, account_id, amount, currency, status, captured_amount, refunded_amount FROM payment_authorizations WHERE owner_id = ? AND id = ? FOR UPDATE",
                rs -> rs.next() ? new PaymentAuthorizationRow(rs.getObject("id", UUID.class), rs.getObject("account_id", UUID.class),
                        rs.getBigDecimal("amount"), rs.getString("currency").trim(), rs.getString("status"),
                        rs.getBigDecimal("captured_amount"), rs.getBigDecimal("refunded_amount")) : null, ownerId, id);
        if (row == null) throw ApiException.notFound("AUTHORIZATION_NOT_FOUND", "Payment authorization not found");
        return row;
    }

    private AccountRow lockAccount(String ownerId, UUID accountId) {
        var row = jdbc.query("SELECT id, status, ledger_balance, pending_debit, currency FROM customer_accounts WHERE owner_id = ? AND id = ? FOR UPDATE",
                rs -> rs.next() ? new AccountRow(rs.getObject("id", UUID.class), rs.getString("status"),
                        rs.getBigDecimal("ledger_balance"), rs.getBigDecimal("pending_debit"), rs.getString("currency").trim()) : null,
                ownerId, accountId);
        if (row == null) throw ApiException.notFound("ACCOUNT_NOT_FOUND", "Account not found");
        return row;
    }

    private Idempotency reserve(String ownerId, String key, String requestHash) {
        jdbc.update("INSERT INTO payment_idempotency(owner_id, idempotency_key, request_hash, created_at) VALUES (?, ?, ?, ?) ON CONFLICT (owner_id, idempotency_key) DO NOTHING",
                ownerId, key, requestHash, OffsetDateTime.now(ZoneOffset.UTC));
        var prior = jdbc.query("SELECT request_hash, resource_type, resource_id FROM payment_idempotency WHERE owner_id = ? AND idempotency_key = ? FOR UPDATE",
                rs -> rs.next() ? new Idempotency(ownerId, rs.getString("request_hash"), rs.getString("resource_type"), rs.getObject("resource_id", UUID.class)) : null,
                ownerId, key);
        if (prior == null) throw idempotencyConflict();
        if (!MessageDigest.isEqual(prior.requestHash().getBytes(StandardCharsets.UTF_8), requestHash.getBytes(StandardCharsets.UTF_8))) {
            throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED", "Idempotency key was already used with a different request");
        }
        return prior.resourceId() == null ? null : prior;
    }

    private void completeIdempotency(String ownerId, String key, String type, UUID id) {
        jdbc.update("UPDATE payment_idempotency SET resource_type = ?, resource_id = ? WHERE owner_id = ? AND idempotency_key = ?",
                type, id, ownerId, key);
    }

    private BigDecimal requestedAmount(CaptureRequest request, BigDecimal remaining, String currency) {
        if (request == null || request.amount() == null) return remaining;
        if (!currency.equals(request.amount().currency())) throw ApiException.unprocessable("CURRENCY_MISMATCH", "Capture currency must match authorization");
        return request.amount().amount().setScale(2);
    }

    private String amountKey(Money amount) {
        return amount == null ? "ALL" : amount.amount().stripTrailingZeros().toPlainString() + "|" + amount.currency();
    }

    private String tokenBehavior(String token) {
        return switch (token) {
            case "tok_demo_approved_001" -> "AUTHORIZED";
            case "tok_demo_decline_funds_01" -> "DECLINED:INSUFFICIENT_FUNDS";
            case "tok_demo_decline_dnh_001" -> "DECLINED:DO_NOT_HONOR";
            case "tok_demo_timeout_001" -> "PENDING";
            default -> throw ApiException.badRequest("UNKNOWN_PAYMENT_TOKEN", "Payment token is not part of the local simulator");
        };
    }

    private TokenOutcome tokenBehaviorResult(String token) {
        var result = tokenBehavior(token);
        if (result.startsWith("DECLINED:")) return new TokenOutcome("DECLINED", result.substring("DECLINED:".length()));
        return new TokenOutcome(result, null);
    }

    private Idempotency idempotencyConflict() {
        throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "Idempotency key cannot be replayed for this operation");
    }

    private Money money(BigDecimal amount, String currency) {
        return new Money(amount.setScale(2), currency);
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalArgumentException("Unable to serialize payment metadata", exception); }
    }

    private String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException("SHA-256 unavailable", exception); }
    }

    private record TokenOutcome(String status, String declineCode) {}
    private record Idempotency(String ownerId, String requestHash, String resourceType, UUID resourceId) {}
    private record AccountRow(UUID id, String status, BigDecimal ledger, BigDecimal pendingDebit, String currency) {}
    private record PaymentAuthorizationRow(UUID id, UUID accountId, BigDecimal amount, String currency, String status,
                                           BigDecimal capturedAmount, BigDecimal refundedAmount) {}
}
