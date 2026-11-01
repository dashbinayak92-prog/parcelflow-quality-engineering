package dev.parcelflow.api.accounts;

import dev.parcelflow.api.errors.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class AccountService {
    private final JdbcTemplate jdbc;

    public AccountService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Account> findAll(String ownerId, int limit, String cursor) {
        if (limit < 1 || limit > 100) {
            throw ApiException.badRequest("INVALID_LIMIT", "limit must be between 1 and 100");
        }
        UUID afterId = decodeCursor(cursor);
        if (afterId == null) {
            return jdbc.query("""
                    SELECT id, display_name, masked_number, account_type, status, ledger_balance,
                           pending_debit, currency, version
                    FROM customer_accounts WHERE owner_id = ? ORDER BY id LIMIT ?
                    """, (rs, row) -> mapAccount(rs), ownerId, limit + 1);
        }
        return jdbc.query("""
                SELECT id, display_name, masked_number, account_type, status, ledger_balance,
                       pending_debit, currency, version
                FROM customer_accounts WHERE owner_id = ? AND id > ? ORDER BY id LIMIT ?
                """, (rs, row) -> mapAccount(rs), ownerId, afterId, limit + 1);
    }

    public String encodeCursor(UUID id) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(id.toString().getBytes(StandardCharsets.UTF_8));
    }

    private UUID decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) return null;
        if (cursor.length() > 256) throw ApiException.badRequest("INVALID_CURSOR", "Cursor is invalid");
        try {
            return UUID.fromString(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException exception) {
            throw ApiException.badRequest("INVALID_CURSOR", "Cursor is invalid");
        }
    }

    public Optional<Account> findById(String ownerId, UUID id) {
        var found = jdbc.query("""
                SELECT id, display_name, masked_number, account_type, status, ledger_balance,
                       pending_debit, currency, version
                FROM customer_accounts WHERE owner_id = ? AND id = ?
                """, (rs, row) -> mapAccount(rs), ownerId, id);
        return found.stream().findFirst();
    }

    private Account mapAccount(java.sql.ResultSet rs) throws java.sql.SQLException {
        var ledger = rs.getBigDecimal("ledger_balance");
        var pending = rs.getBigDecimal("pending_debit");
        var currency = rs.getString("currency").trim();
        var available = ledger.subtract(pending);
        var asOf = OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
        return new Account(rs.getObject("id", UUID.class), rs.getString("display_name"),
                rs.getString("masked_number"), Account.AccountType.valueOf(rs.getString("account_type")),
                Account.AccountStatus.valueOf(rs.getString("status")),
                new Account.Balance(money(ledger, currency), money(available, currency), money(pending, currency), asOf),
                rs.getLong("version"));
    }

    private Account.Money money(BigDecimal amount, String currency) {
        return new Account.Money(amount.setScale(2), currency);
    }
}
