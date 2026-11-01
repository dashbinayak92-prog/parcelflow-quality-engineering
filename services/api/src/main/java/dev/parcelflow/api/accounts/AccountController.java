package dev.parcelflow.api.accounts;

import dev.parcelflow.api.errors.ApiException;
import dev.parcelflow.api.security.CustomerIdentity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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
    private final CustomerIdentity customerIdentity;

    public AccountController(AccountService accountService, CustomerIdentity customerIdentity) {
        this.accountService = accountService;
        this.customerIdentity = customerIdentity;
    }

    @GetMapping
    public AccountPage list(@AuthenticationPrincipal Jwt principal,
                            @RequestParam(defaultValue = "20") int limit,
                            @RequestParam(required = false) String cursor) {
        var fetched = accountService.findAll(customerIdentity.ownerId(principal), limit, cursor);
        var hasMore = fetched.size() > limit;
        var items = hasMore ? fetched.subList(0, limit) : fetched;
        var nextCursor = hasMore ? accountService.encodeCursor(items.get(items.size() - 1).id()) : null;
        return new AccountPage(items, new AccountPage.PageInfo(nextCursor, hasMore));
    }

    @GetMapping("/{accountId}")
    public Account get(@AuthenticationPrincipal Jwt principal, @PathVariable UUID accountId) {
        return accountService.findById(customerIdentity.ownerId(principal), accountId)
                .orElseThrow(() -> ApiException.notFound("ACCOUNT_NOT_FOUND", "Account not found"));
    }
}
