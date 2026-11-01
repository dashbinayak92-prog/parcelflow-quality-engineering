package dev.parcelflow.api.transfers;

import dev.parcelflow.api.errors.ApiException;
import dev.parcelflow.api.security.CustomerIdentity;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {
    private final TransferService transferService;
    private final CustomerIdentity customerIdentity;

    public TransferController(TransferService transferService, CustomerIdentity customerIdentity) {
        this.transferService = transferService;
        this.customerIdentity = customerIdentity;
    }

    @PostMapping
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    public TransferRequest.Result create(
            @AuthenticationPrincipal Jwt principal,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {
        if (idempotencyKey.length() < 8 || idempotencyKey.length() > 128) {
            throw ApiException.badRequest("INVALID_IDEMPOTENCY_KEY", "Idempotency-Key must contain 8 to 128 characters");
        }
        return transferService.transfer(customerIdentity.ownerId(principal), idempotencyKey, request);
    }
}
