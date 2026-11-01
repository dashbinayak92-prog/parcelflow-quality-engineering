package dev.parcelflow.api.transactions;

import dev.parcelflow.api.errors.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
public class TransactionService {
    private final JdbcTemplate jdbc;

    public TransactionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<TransactionPage.Entry> find(String ownerId, UUID accountId, String status,
                                            OffsetDateTime from, OffsetDateTime to, String cursor, int limit) {
        if (limit < 1 || limit > 100) throw ApiException.badRequest("INVALID_LIMIT", "limit must be between 1 and 100");
        if (from != null && to != null && !from.isBefore(to)) {
            throw ApiException.badRequest("INVALID_DATE_RANGE", "from must be earlier than to");
        }
        var after = decodeCursor(cursor);
        var sql = new StringBuilder("""
                SELECT t.id, t.entry_type, t.status, t.amount, t.currency, t.description,
                       COALESCE(t.payment_authorization_id, t.transfer_id) AS related_id, t.created_at, t.posted_at
                FROM account_transactions t
                JOIN customer_accounts a ON a.id = t.account_id
                WHERE a.owner_id = ? AND t.account_id = ?
                """);
        var args = new ArrayList<Object>();
        args.add(ownerId);
        args.add(accountId);
        if (status != null && !status.isBlank()) {
            sql.append(" AND t.status = ?");
            sql.append(" ");
            args.add(status);
        }
        if (from != null) {
            sql.append(" AND t.created_at >= ?");
            args.add(from);
        }
        if (to != null) {
            sql.append(" AND t.created_at < ?");
            args.add(to);
        }
        if (after != null) {
            sql.append(" AND (t.created_at, t.id) < (?, ?)");
            args.add(after.createdAt());
            args.add(after.id());
        }
        sql.append(" ORDER BY t.created_at DESC, t.id DESC LIMIT ?");
        args.add(limit + 1);
        return jdbc.query(sql.toString(), (rs, row) -> new TransactionPage.Entry(
                rs.getObject("id", UUID.class), rs.getString("entry_type"), rs.getString("status"),
                new dev.parcelflow.api.accounts.Account.Money(rs.getBigDecimal("amount"), rs.getString("currency").trim()),
                rs.getString("description"), rs.getObject("related_id", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("posted_at", OffsetDateTime.class)),
                args.toArray());
    }

    public String encodeCursor(TransactionPage.Entry entry) {
        var value = entry.createdAt().toString() + "|" + entry.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private Cursor decodeCursor(String encoded) {
        if (encoded == null || encoded.isBlank()) return null;
        if (encoded.length() > 512) throw ApiException.badRequest("INVALID_CURSOR", "Cursor is invalid");
        try {
            var decoded = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            var separator = decoded.lastIndexOf('|');
            if (separator <= 0 || separator == decoded.length() - 1) throw new IllegalArgumentException("Malformed cursor");
            var timestamp = OffsetDateTime.parse(decoded.substring(0, separator));
            var id = UUID.fromString(decoded.substring(separator + 1));
            return new Cursor(timestamp, id);
        } catch (RuntimeException exception) {
            throw ApiException.badRequest("INVALID_CURSOR", "Cursor is invalid");
        }
    }

    private record Cursor(OffsetDateTime createdAt, UUID id) {}
}
