package dev.parcelflow.api.payments;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public final class PaymentDtos {
    private PaymentDtos() {}

    public enum CaptureMode { MANUAL, AUTOMATIC }

    public record Money(
            @NotNull @DecimalMin(value = "0.00", inclusive = false) @Digits(integer = 12, fraction = 2)
            @JsonSerialize(using = ToStringSerializer.class) BigDecimal amount,
            @NotNull @Pattern(regexp = "^[A-Z]{3}$") String currency) {}

    public record AuthorizationRequest(
            @NotNull UUID accountId,
            @NotBlank @Pattern(regexp = "^tok_[A-Za-z0-9_-]{12,80}$") String paymentToken,
            @NotNull @Valid Money amount,
            @NotBlank @Size(max = 64) String merchantReference,
            CaptureMode captureMode,
            @Size(max = 10) Map<@NotBlank @Size(max = 40) String, @NotNull @Size(max = 256) String> metadata) {
        public AuthorizationRequest {
            if (captureMode == null) captureMode = CaptureMode.MANUAL;
        }
    }

    public record CaptureRequest(@Valid Money amount) {}
    public record RefundRequest(@NotNull @Valid Money amount) {}

    public record Authorization(UUID id, String status, Money amount, Money capturedAmount,
                                String declineCode, OffsetDateTime createdAt, OffsetDateTime expiresAt) {}

    public record Operation(UUID id, UUID authorizationId, String type, String status, Money amount,
                            OffsetDateTime createdAt) {}
}
