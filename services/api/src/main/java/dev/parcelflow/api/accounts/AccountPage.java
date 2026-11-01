package dev.parcelflow.api.accounts;

import java.util.List;

public record AccountPage(List<Account> items, PageInfo page) {
    public AccountPage {
        items = List.copyOf(items);
    }

    public record PageInfo(String nextCursor, boolean hasMore) {}
}
