package dev.parcelflow.api.accounts;

import dev.parcelflow.api.DemoPrincipal;
import dev.parcelflow.api.errors.ApiException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {
    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping
    public AccountPage list(@RequestParam(defaultValue = "20") int limit,
                            @RequestParam(required = false) String cursor) {
        var fetched = accountService.findAll(DemoPrincipal.CUSTOMER_ID, limit, cursor);
        var hasMore = fetched.size() > limit;
        var items = hasMore ? fetched.subList(0, limit) : fetched;
        var nextCursor = hasMore ? accountService.encodeCursor(items.get(items.size() - 1).id()) : null;
        return new AccountPage(items, new AccountPage.PageInfo(nextCursor, hasMore));
    }

    @GetMapping("/{accountId}")
    public Account get(@PathVariable UUID accountId) {
        return accountService.findById(DemoPrincipal.CUSTOMER_ID, accountId)
                .orElseThrow(() -> ApiException.notFound("ACCOUNT_NOT_FOUND", "Account not found"));
    }
}
