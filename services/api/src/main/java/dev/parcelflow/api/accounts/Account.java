package dev.parcelflow.api.accounts;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record Account(
        UUID id,
        String displayName,
        String maskedNumber,
        AccountType type,
        AccountStatus status,
        Balance balance,
        long version) {
    public enum AccountType { CHECKING, SAVINGS }
    public enum AccountStatus { ACTIVE, FROZEN, CLOSED }

    public record Money(BigDecimal amount, String currency) {}
    public record Balance(Money ledger, Money available, Money pendingDebit, OffsetDateTime asOf) {}
}
