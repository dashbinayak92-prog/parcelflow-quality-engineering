# Identity and access decisions

- The service is an OAuth 2.0 Resource Server and delegates identity and token issuance to an OIDC provider. Local development uses a Keycloak realm import; production configuration points to a separately managed issuer.
- JWT validation checks signature, issuer, timestamp claims, and the `parcelflow-api` audience through Spring Security. Realm roles map to `ROLE_CUSTOMER` and `ROLE_OPERATIONS`.
- Account ownership is resolved from the verified token's `preferred_username` claim. Controllers do not accept an owner/customer ID from request payloads.
- Account, transaction, and transfer routes require the customer role. Unauthenticated access gets 401; a valid token with the wrong role gets 403.
- The integration test decoder accepts only named synthetic test tokens and is imported only in test configuration. The production path uses issuer discovery and the issuer's JWKS.
- Keycloak's local `start-dev` mode and the included demo passwords are fixtures for local automation only. No real account data or payment credentials are in the realm.
- Production deployment still needs an explicitly configured issuer URL, audience, TLS termination, secret management, and provider-managed realm/client configuration.
