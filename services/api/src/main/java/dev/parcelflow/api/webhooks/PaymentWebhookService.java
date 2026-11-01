package dev.parcelflow.api.webhooks;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.parcelflow.api.errors.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class PaymentWebhookService {
    private static final long MAX_CLOCK_SKEW_SECONDS = 300;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final byte[] secret;

    public PaymentWebhookService(JdbcTemplate jdbc, ObjectMapper objectMapper,
                                 @Value("${parcelflow.payments.webhook-secret}") String secret) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        if (secret == null || secret.isBlank()) throw new IllegalStateException("Webhook signing secret must not be empty");
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    @Transactional
    public WebhookReceipt accept(String timestampHeader, String signatureHeader, byte[] rawBody) {
        verifySignature(timestampHeader, signatureHeader, rawBody);
        var payload = new String(rawBody, StandardCharsets.UTF_8);
        var event = parse(payload);
        var eventId = parseEventId(event.id());
        if (!"payment.operation.succeeded".equals(event.type())) {
            throw ApiException.badRequest("UNSUPPORTED_WEBHOOK_EVENT", "Payment event type is not supported by the simulator");
        }
        if (event.authorizationId() == null || event.operationId() == null || event.occurredAt() == null) {
            throw ApiException.badRequest("INVALID_WEBHOOK_EVENT", "Payment event must identify its authorization, operation, and occurrence time");
        }

        var payloadHash = sha256(rawBody);
        var now = OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
        var inserted = jdbc.update("""
                INSERT INTO payment_webhook_inbox(event_id, event_type, authorization_id, operation_id,
                    payload_hash, status, delivery_attempts, received_at)
                VALUES (?, ?, ?, ?, ?, 'RECEIVED', 1, ?)
                ON CONFLICT (event_id) DO NOTHING
                """, eventId, event.type(), event.authorizationId(), event.operationId(), payloadHash, now);

        var stored = jdbc.query("""
                SELECT event_type, authorization_id, operation_id, payload_hash, status, delivery_attempts
                FROM payment_webhook_inbox WHERE event_id = ? FOR UPDATE
                """, rs -> rs.next() ? new StoredEvent(rs.getString("event_type"),
                rs.getObject("authorization_id", UUID.class), rs.getObject("operation_id", UUID.class),
                rs.getString("payload_hash"), rs.getString("status"), rs.getInt("delivery_attempts")) : null, eventId);
        if (stored == null) throw ApiException.conflict("WEBHOOK_EVENT_NOT_STORED", "Webhook event could not be stored");
        if (!MessageDigest.isEqual(stored.payloadHash().getBytes(StandardCharsets.UTF_8), payloadHash.getBytes(StandardCharsets.UTF_8))) {
            throw ApiException.conflict("WEBHOOK_EVENT_ID_REUSED", "Webhook event ID was reused with a different payload");
        }

        var attempts = stored.deliveryAttempts();
        if (inserted == 0) {
            attempts = jdbc.queryForObject("UPDATE payment_webhook_inbox SET delivery_attempts = delivery_attempts + 1 WHERE event_id = ? RETURNING delivery_attempts",
                    Integer.class, eventId);
        }
        if (!"RECEIVED".equals(stored.status())) {
            return new WebhookReceipt(eventId, stored.status(), attempts, null);
        }

        var operationMatches = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM payment_operations
                WHERE id = ? AND authorization_id = ? AND status = 'SUCCEEDED')
                """, Boolean.class, event.operationId(), event.authorizationId());
        if (!Boolean.TRUE.equals(operationMatches)) {
            jdbc.update("UPDATE payment_webhook_inbox SET last_error = 'OPERATION_NOT_AVAILABLE' WHERE event_id = ?", eventId);
            return new WebhookReceipt(eventId, "RECEIVED", attempts, "OPERATION_NOT_AVAILABLE");
        }

        jdbc.update("UPDATE payment_webhook_inbox SET status = 'PROCESSED', last_error = NULL, processed_at = ? WHERE event_id = ?",
                now, eventId);
        return new WebhookReceipt(eventId, "PROCESSED", attempts, null);
    }

    private void verifySignature(String timestampHeader, String signatureHeader, byte[] rawBody) {
        final long sentAt;
        try {
            sentAt = Long.parseLong(timestampHeader);
        } catch (NumberFormatException exception) {
            throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "INVALID_WEBHOOK_TIMESTAMP",
                    "Webhook timestamp is invalid");
        }
        if (Math.abs(Instant.now().getEpochSecond() - sentAt) > MAX_CLOCK_SKEW_SECONDS) {
            throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "STALE_WEBHOOK", "Webhook timestamp is outside the accepted window");
        }
        if (signatureHeader == null || !signatureHeader.startsWith("sha256=")) {
            throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "INVALID_WEBHOOK_SIGNATURE", "Webhook signature is invalid");
        }
        var signedContent = timestampHeader + "." + new String(rawBody, StandardCharsets.UTF_8);
        var expected = hmac(signedContent);
        var supplied = signatureHeader.substring("sha256=".length());
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), supplied.getBytes(StandardCharsets.US_ASCII))) {
            throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "INVALID_WEBHOOK_SIGNATURE", "Webhook signature is invalid");
        }
    }

    private WebhookEvent parse(String payload) {
        try {
            return objectMapper.readValue(payload, WebhookEvent.class);
        } catch (JsonProcessingException exception) {
            throw ApiException.badRequest("INVALID_WEBHOOK_EVENT", "Webhook payload is not a valid event");
        }
    }

    private UUID parseEventId(String value) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException exception) {
            throw ApiException.badRequest("INVALID_WEBHOOK_EVENT_ID", "Webhook event ID must be a UUID");
        }
    }

    private String hmac(String signedContent) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(signedContent.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record WebhookEvent(String id, String type, UUID authorizationId, UUID operationId,
                               OffsetDateTime occurredAt) {}
    public record WebhookReceipt(UUID eventId, String status, int deliveryAttempts, String lastError) {}
    private record StoredEvent(String eventType, UUID authorizationId, UUID operationId,
                               String payloadHash, String status, int deliveryAttempts) {}
}
