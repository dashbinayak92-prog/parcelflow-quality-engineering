package dev.parcelflow.api.transactions;

import dev.parcelflow.api.accounts.Account;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record TransactionPage(List<Entry> items, PageInfo page) {
    public TransactionPage {
        items = List.copyOf(items);
    }

    public record Entry(UUID id, String type, String status, Account.Money amount, String description,
                        UUID relatedTransactionId, OffsetDateTime createdAt, OffsetDateTime postedAt) {}
    public record PageInfo(String nextCursor, boolean hasMore) {}
}
