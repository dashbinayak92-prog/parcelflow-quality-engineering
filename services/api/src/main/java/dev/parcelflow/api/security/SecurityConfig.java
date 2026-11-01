package dev.parcelflow.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http,
                                    Converter<Jwt, ? extends AbstractAuthenticationToken> jwtAuthenticationConverter,
                                    ObjectMapper objectMapper)
            throws Exception {
        var authenticationEntryPoint = authenticationEntryPoint(objectMapper);
        var accessDeniedHandler = accessDeniedHandler(objectMapper);
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/webhooks/**").permitAll()
                        .requestMatchers("/api/v1/operations/**").hasRole("OPERATIONS")
                        .requestMatchers("/api/v1/accounts/**", "/api/v1/transfers/**", "/api/v1/payments/**").hasRole("CUSTOMER")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
                .build();
    }

    private AuthenticationEntryPoint authenticationEntryPoint(ObjectMapper objectMapper) {
        return (request, response, exception) -> {
            response.setHeader("WWW-Authenticate", "Bearer");
            writeSecurityProblem(objectMapper, request, response, HttpStatus.UNAUTHORIZED,
                    "UNAUTHENTICATED", "Authentication required");
        };
    }

    private AccessDeniedHandler accessDeniedHandler(ObjectMapper objectMapper) {
        return (request, response, exception) -> writeSecurityProblem(objectMapper, request, response,
                HttpStatus.FORBIDDEN, "FORBIDDEN", "The access token does not grant permission for this operation");
    }

    private void writeSecurityProblem(ObjectMapper objectMapper, HttpServletRequest request,
                                      HttpServletResponse response, HttpStatus status,
                                      String code, String detail) throws IOException {
        var body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setType(URI.create("https://parcelflow.dev/problems/" + code.toLowerCase(Locale.ROOT)));
        body.setTitle(code.replace('_', ' '));
        body.setProperty("code", code);
        body.setProperty("traceId", UUID.randomUUID().toString());
        response.setStatus(status.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/problem+json");
        objectMapper.writeValue(response.getOutputStream(), body);
    }

    @Bean
    Converter<Jwt, ? extends AbstractAuthenticationToken> jwtAuthenticationConverter() {
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(this::realmAuthorities);
        return converter;
    }

    private Collection<GrantedAuthority> realmAuthorities(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess == null || !(realmAccess.get("roles") instanceof Collection<?> roles)) return List.of();
        return roles.stream().filter(String.class::isInstance).map(String.class::cast)
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role.toUpperCase(Locale.ROOT)))
                .toList();
    }
}
