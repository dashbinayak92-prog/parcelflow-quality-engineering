package dev.parcelflow.api.accounts;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
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

    public record Money(@JsonSerialize(using = ToStringSerializer.class) BigDecimal amount, String currency) {}
    public record Balance(Money ledger, Money available, Money pendingDebit, OffsetDateTime asOf) {}
}
