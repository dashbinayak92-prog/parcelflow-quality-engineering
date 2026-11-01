package dev.parcelflow.api.transfers;

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

@Service
public class TransferService {
    private static final BigDecimal MAX_SINGLE_TRANSFER = new BigDecimal("5000.00");
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public TransferService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public TransferRequest.Result transfer(String ownerId, String idempotencyKey, TransferRequest request) {
        if (request.sourceAccountId().equals(request.destinationAccountId())) {
            throw ApiException.unprocessable("SAME_ACCOUNT", "Source and destination accounts must differ");
        }
        var amount = request.amount().amount().setScale(2);
        if (amount.compareTo(MAX_SINGLE_TRANSFER) > 0) {
            throw ApiException.unprocessable("TRANSFER_LIMIT_EXCEEDED", "Transfer exceeds the per-transfer demo limit");
        }
        var metadata = request.metadata() == null ? Map.<String, String>of() : new TreeMap<>(request.metadata());
        var metadataJson = toJson(metadata);
        var requestHash = hash(request, metadataJson);
        jdbc.update("""
                INSERT INTO transfer_idempotency(owner_id, idempotency_key, request_hash, transfer_id, created_at)
                VALUES (?, ?, ?, NULL, ?)
                ON CONFLICT (owner_id, idempotency_key) DO NOTHING
                """, ownerId, idempotencyKey, requestHash, OffsetDateTime.now(ZoneOffset.UTC));
        Prior previous = jdbc.query("""
                SELECT request_hash, transfer_id FROM transfer_idempotency
                WHERE owner_id = ? AND idempotency_key = ? FOR UPDATE
                """, rs -> rs.next() ? new Prior(rs.getString(1), rs.getObject(2, UUID.class)) : null,
                ownerId, idempotencyKey);
        if (previous == null) {
            throw ApiException.conflict("IDEMPOTENCY_CONFLICT", "Idempotency record could not be acquired");
        }
        if (!MessageDigest.isEqual(previous.requestHash().getBytes(StandardCharsets.UTF_8),
                requestHash.getBytes(StandardCharsets.UTF_8))) {
            throw ApiException.conflict("IDEMPOTENCY_KEY_REUSED", "Idempotency key was already used with a different request");
        }
        if (previous.transferId() != null) return findResult(previous.transferId());

        var accounts = lockAccounts(ownerId, request.sourceAccountId(), request.destinationAccountId());
        var source = accounts.stream().filter(account -> account.id().equals(request.sourceAccountId())).findFirst()
                .orElseThrow(() -> ApiException.notFound("SOURCE_ACCOUNT_NOT_FOUND", "Source account not found"));
        var destination = accounts.stream().filter(account -> account.id().equals(request.destinationAccountId())).findFirst()
                .orElseThrow(() -> ApiException.notFound("DESTINATION_ACCOUNT_NOT_FOUND", "Destination account not found"));
        if (!"ACTIVE".equals(source.status()) || !"ACTIVE".equals(destination.status())) {
            throw ApiException.unprocessable("ACCOUNT_NOT_ACTIVE", "Both accounts must be active");
        }
        if (!source.currency().equals(destination.currency()) || !source.currency().equals(request.amount().currency())) {
            throw ApiException.unprocessable("CURRENCY_MISMATCH", "Transfer currency must match both accounts");
        }
        if (source.ledger().subtract(source.pendingDebit()).compareTo(amount) < 0) {
            throw ApiException.unprocessable("INSUFFICIENT_FUNDS", "Available balance is insufficient");
        }

        var now = OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
        var transferId = UUID.randomUUID();
        var debitId = UUID.randomUUID();
        var creditId = UUID.randomUUID();
        jdbc.update("UPDATE customer_accounts SET ledger_balance = ?, version = version + 1 WHERE id = ?",
                source.ledger().subtract(amount), source.id());
        jdbc.update("UPDATE customer_accounts SET ledger_balance = ?, version = version + 1 WHERE id = ?",
                destination.ledger().add(amount), destination.id());
        jdbc.update("""
                INSERT INTO transfers(id, owner_id, source_account_id, destination_account_id, amount, currency, status, memo, metadata, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 'POSTED', ?, ?::jsonb, ?)
                """, transferId, ownerId, source.id(), destination.id(), amount, source.currency(), request.memo(), metadataJson, now);
        insertEntry(debitId, transferId, source.id(), "TRANSFER_DEBIT", amount, source.currency(), request.memo(), now);
        insertEntry(creditId, transferId, destination.id(), "TRANSFER_CREDIT", amount, source.currency(), request.memo(), now);
        jdbc.update("UPDATE transfer_idempotency SET transfer_id = ? WHERE owner_id = ? AND idempotency_key = ?",
                transferId, ownerId, idempotencyKey);
        return new TransferRequest.Result(transferId, "POSTED",
                new TransferRequest.Amount(amount, source.currency()), debitId, creditId, now);
    }

    private List<LockedAccount> lockAccounts(String ownerId, UUID sourceId, UUID destinationId) {
        return jdbc.query("""
                SELECT id, status, ledger_balance, pending_debit, currency
                FROM customer_accounts WHERE owner_id = ? AND id IN (?, ?) ORDER BY id FOR UPDATE
                """, (rs, row) -> new LockedAccount(rs.getObject("id", UUID.class), rs.getString("status"),
                rs.getBigDecimal("ledger_balance"), rs.getBigDecimal("pending_debit"), rs.getString("currency").trim()),
                ownerId, sourceId, destinationId);
    }

    private void insertEntry(UUID id, UUID transferId, UUID accountId, String type, BigDecimal amount,
                             String currency, String memo, OffsetDateTime now) {
        jdbc.update("""
                INSERT INTO account_transactions(id, transfer_id, account_id, entry_type, status, amount, currency, description, created_at, posted_at)
                VALUES (?, ?, ?, ?, 'POSTED', ?, ?, ?, ?, ?)
                """, id, transferId, accountId, type, amount, currency,
                memo == null || memo.isBlank() ? "Internal transfer" : memo, now, now);
    }

    private TransferRequest.Result findResult(UUID transferId) {
        TransferHeader header = jdbc.query("""
                SELECT id, amount, currency, created_at FROM transfers WHERE id = ?
                """, rs -> rs.next() ? new TransferHeader(rs.getObject("id", UUID.class), rs.getBigDecimal("amount"),
                rs.getString("currency").trim(), rs.getObject("created_at", OffsetDateTime.class)) : null, transferId);
        if (header == null) {
            throw ApiException.conflict("IDEMPOTENCY_RESULT_MISSING", "Prior transfer result is unavailable");
        }
        var transactions = jdbc.query("SELECT id, entry_type FROM account_transactions WHERE transfer_id = ?",
                (rs, row) -> new TransactionKey(rs.getObject("id", UUID.class), rs.getString("entry_type")), transferId);
        var debitId = transactions.stream().filter(tx -> "TRANSFER_DEBIT".equals(tx.type())).findFirst()
                .orElseThrow(() -> ApiException.conflict("IDEMPOTENCY_RESULT_MISSING", "Debit entry is unavailable")).id();
        var creditId = transactions.stream().filter(tx -> "TRANSFER_CREDIT".equals(tx.type())).findFirst()
                .orElseThrow(() -> ApiException.conflict("IDEMPOTENCY_RESULT_MISSING", "Credit entry is unavailable")).id();
        return new TransferRequest.Result(header.id(), "POSTED",
                new TransferRequest.Amount(header.amount(), header.currency()), debitId, creditId, header.createdAt());
    }

    private String toJson(Map<String, String> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Transfer metadata cannot be serialized", exception);
        }
    }

    private String hash(TransferRequest request, String metadataJson) {
        try {
            var canonical = new TreeMap<String, String>();
            canonical.put("sourceAccountId", request.sourceAccountId().toString());
            canonical.put("destinationAccountId", request.destinationAccountId().toString());
            canonical.put("amount", request.amount().amount().stripTrailingZeros().toPlainString());
            canonical.put("currency", request.amount().currency());
            canonical.put("memo", request.memo() == null ? "" : request.memo());
            canonical.put("metadata", metadataJson);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(objectMapper.writeValueAsBytes(canonical)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to compute idempotency fingerprint", exception);
        }
    }

    private record LockedAccount(UUID id, String status, BigDecimal ledger, BigDecimal pendingDebit, String currency) {}
    private record Prior(String requestHash, UUID transferId) {}
    private record TransferHeader(UUID id, BigDecimal amount, String currency, OffsetDateTime createdAt) {}
    private record TransactionKey(UUID id, String type) {}
}
