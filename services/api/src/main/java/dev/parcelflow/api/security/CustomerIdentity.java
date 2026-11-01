package dev.parcelflow.api.security;

import dev.parcelflow.api.errors.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class CustomerIdentity {
    public String ownerId(Jwt jwt) {
        var ownerId = jwt.getClaimAsString("preferred_username");
        if (ownerId == null || ownerId.isBlank()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "CUSTOMER_IDENTITY_MISSING",
                    "The access token does not identify a customer");
        }
        return ownerId;
    }
}
