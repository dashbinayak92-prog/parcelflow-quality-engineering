package dev.parcelflow.api.transactions;

import dev.parcelflow.api.DemoPrincipal;
import dev.parcelflow.api.accounts.AccountService;
import dev.parcelflow.api.errors.ApiException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/accounts/{accountId}/transactions")
public class AccountTransactionController {
    private static final Set<String> VALID_STATUSES = Set.of("PENDING", "POSTED", "REVERSED", "DECLINED");
    private final AccountService accountService;
    private final TransactionService transactionService;

    public AccountTransactionController(AccountService accountService, TransactionService transactionService) {
        this.accountService = accountService;
        this.transactionService = transactionService;
    }

    @GetMapping
    public TransactionPage list(
            @PathVariable UUID accountId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) OffsetDateTime from,
            @RequestParam(required = false) OffsetDateTime to,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        var ownerId = DemoPrincipal.CUSTOMER_ID;
        if (accountService.findById(ownerId, accountId).isEmpty()) {
            throw ApiException.notFound("ACCOUNT_NOT_FOUND", "Account not found");
        }
        if (status != null && !VALID_STATUSES.contains(status)) {
            throw ApiException.badRequest("INVALID_TRANSACTION_STATUS", "status is not supported");
        }
        var fetched = transactionService.find(ownerId, accountId, status, from, to, cursor, limit);
        var hasMore = fetched.size() > limit;
        var items = hasMore ? fetched.subList(0, limit) : fetched;
        var nextCursor = hasMore ? transactionService.encodeCursor(items.get(items.size() - 1)) : null;
        return new TransactionPage(items, new TransactionPage.PageInfo(nextCursor, hasMore));
    }
}
