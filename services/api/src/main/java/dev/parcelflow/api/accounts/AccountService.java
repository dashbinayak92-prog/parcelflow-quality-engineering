package dev.parcelflow.api.accounts;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class AccountService {
    private static final UUID CHECKING_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID SAVINGS_ID = UUID.fromString("10000000-0000-4000-8000-000000000002");

    public List<Account> findAll() {
        return List.of(
                account(CHECKING_ID, "Everyday Checking", "****4821", Account.AccountType.CHECKING,
                        "2840.50", "2615.50", "225.00", 7),
                account(SAVINGS_ID, "Rainy Day Savings", "****9074", Account.AccountType.SAVINGS,
                        "12500.00", "12500.00", "0.00", 3));
    }

    public Optional<Account> findById(UUID id) {
        return findAll().stream().filter(account -> account.id().equals(id)).findFirst();
    }

    private Account account(UUID id, String name, String maskedNumber, Account.AccountType type,
                            String ledger, String available, String pending, long version) {
        var asOf = OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
        return new Account(id, name, maskedNumber, type, Account.AccountStatus.ACTIVE,
                new Account.Balance(money(ledger), money(available), money(pending), asOf), version);
    }

    private Account.Money money(String amount) {
        return new Account.Money(new BigDecimal(amount), "USD");
    }
}
