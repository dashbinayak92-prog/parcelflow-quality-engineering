package dev.parcelflow.api.payments;

import dev.parcelflow.api.errors.ApiException;
import dev.parcelflow.api.security.CustomerIdentity;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static dev.parcelflow.api.payments.PaymentDtos.*;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {
    private final PaymentService paymentService;
    private final CustomerIdentity customerIdentity;

    public PaymentController(PaymentService paymentService, CustomerIdentity customerIdentity) {
        this.paymentService = paymentService;
        this.customerIdentity = customerIdentity;
    }

    @PostMapping("/authorizations")
    @ResponseStatus(HttpStatus.CREATED)
    public Authorization authorize(@AuthenticationPrincipal Jwt principal,
                                    @RequestHeader("Idempotency-Key") String key,
                                    @Valid @RequestBody AuthorizationRequest request) {
        validateKey(key);
        return paymentService.authorize(customerIdentity.ownerId(principal), key, request);
    }

    @PostMapping("/authorizations/{authorizationId}/capture")
    public Operation capture(@AuthenticationPrincipal Jwt principal,
                             @PathVariable UUID authorizationId,
                             @RequestHeader("Idempotency-Key") String key,
                             @Valid @RequestBody(required = false) CaptureRequest request) {
        validateKey(key);
        return paymentService.capture(customerIdentity.ownerId(principal), authorizationId, key, request);
    }

    @PostMapping("/authorizations/{authorizationId}/void")
    public Operation voidAuthorization(@AuthenticationPrincipal Jwt principal,
                                       @PathVariable UUID authorizationId,
                                       @RequestHeader("Idempotency-Key") String key) {
        validateKey(key);
        return paymentService.voidAuthorization(customerIdentity.ownerId(principal), authorizationId, key);
    }

    @PostMapping("/authorizations/{authorizationId}/refunds")
    @ResponseStatus(HttpStatus.CREATED)
    public Operation refund(@AuthenticationPrincipal Jwt principal,
                            @PathVariable UUID authorizationId,
                            @RequestHeader("Idempotency-Key") String key,
                            @Valid @RequestBody RefundRequest request) {
        validateKey(key);
        return paymentService.refund(customerIdentity.ownerId(principal), authorizationId, key, request);
    }

    @org.springframework.web.bind.annotation.GetMapping("/authorizations/{authorizationId}")
    public Authorization getAuthorization(@AuthenticationPrincipal Jwt principal,
                                          @PathVariable UUID authorizationId) {
        return paymentService.findAuthorization(customerIdentity.ownerId(principal), authorizationId);
    }

    private void validateKey(String key) {
        if (key.length() < 8 || key.length() > 128) {
            throw ApiException.badRequest("INVALID_IDEMPOTENCY_KEY", "Idempotency-Key must contain 8 to 128 characters");
        }
    }
}
