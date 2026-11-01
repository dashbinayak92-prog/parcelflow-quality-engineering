package dev.parcelflow.api.reconciliation;

import dev.parcelflow.api.accounts.Account;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service
public class ReconciliationService {
    private final JdbcTemplate jdbc;

    public ReconciliationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Report reconcileAccounts() {
        var rows = jdbc.query("""
                SELECT a.id, a.display_name, a.currency, a.opening_ledger_balance, a.ledger_balance,
                       COALESCE(SUM(CASE
                           WHEN t.status <> 'POSTED' THEN 0
                           WHEN t.entry_type IN ('TRANSFER_DEBIT', 'PAYMENT_CAPTURE') THEN -t.amount
                           WHEN t.entry_type IN ('TRANSFER_CREDIT', 'PAYMENT_REFUND') THEN t.amount
                           ELSE 0
                       END), 0) AS posted_delta
                FROM customer_accounts a
                LEFT JOIN account_transactions t ON t.account_id = a.id
                GROUP BY a.id, a.display_name, a.currency, a.opening_ledger_balance, a.ledger_balance
                ORDER BY a.id
                """, (rs, row) -> {
            var expected = rs.getBigDecimal("opening_ledger_balance").add(rs.getBigDecimal("posted_delta"));
            var ledger = rs.getBigDecimal("ledger_balance");
            var variance = ledger.subtract(expected).setScale(2);
            return new AccountCheck(rs.getObject("id", UUID.class), rs.getString("display_name"),
                    new Account.Money(ledger.setScale(2), rs.getString("currency").trim()),
                    new Account.Money(expected.setScale(2), rs.getString("currency").trim()),
                    new Account.Money(variance, rs.getString("currency").trim()),
                    variance.signum() == 0 ? "MATCH" : "MISMATCH");
        });
        var mismatches = rows.stream().filter(row -> "MISMATCH".equals(row.status())).count();
        return new Report("COMPLETED", rows.size(), (int) mismatches,
                OffsetDateTime.now(ZoneOffset.UTC), List.copyOf(rows));
    }

    public record Report(String status, int accountCount, int mismatchCount,
                         OffsetDateTime completedAt, List<AccountCheck> accounts) {}
    public record AccountCheck(UUID accountId, String accountName, Account.Money ledgerBalance,
                               Account.Money expectedBalance, Account.Money variance, String status) {}
}
