package dev.parcelflow.api.transfers;

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

public record TransferRequest(
        @NotNull UUID sourceAccountId,
        @NotNull UUID destinationAccountId,
        @NotNull @Valid Amount amount,
        @Size(max = 140) String memo,
        @Size(max = 10) Map<@NotBlank @Size(max = 40) String, @NotNull @Size(max = 256) String> metadata) {
    public record Amount(
            @NotNull @DecimalMin(value = "0.00", inclusive = false) @Digits(integer = 12, fraction = 2)
            @JsonSerialize(using = ToStringSerializer.class) BigDecimal amount,
            @NotNull @Pattern(regexp = "^[A-Z]{3}$") String currency) {}

    public record Result(UUID id, String status, Amount amount, UUID sourceTransactionId,
                         UUID destinationTransactionId, OffsetDateTime createdAt) {}
}
