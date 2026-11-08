package com.example.springbootbasiclogin.config;

import com.example.springbootbasiclogin.constant.AuthResponseCode;
import com.example.springbootbasiclogin.dto.exception.ErrorResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableReactiveMethodSecurity;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.header.XFrameOptionsServerHttpHeadersWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebFluxSecurity
@EnableReactiveMethodSecurity
@RequiredArgsConstructor
@Slf4j
public class SecurityConfig {

    private final JwtAuthenticationWebFilter jwtAuthenticationWebFilter;
    private final ApplicationPropertiesConfig applicationProperties;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<Tracer> tracerProvider;
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        List<String> origins = applicationProperties.getApp().getAllowedOrigins();
        if (origins == null || origins.isEmpty()) {
            origins = List.of(applicationProperties.getApp().getBaseUrl());
        }
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "HEAD", "PATCH"));
        configuration.setAllowedHeaders(Arrays.asList("Authorization", "Content-Type", "Accept", "X-Requested-With", "Origin"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    private Mono<Void> writeSecurityError(ServerWebExchange exchange, HttpStatus status, String code, String message) {
        if (exchange == null) {
            return Mono.empty();
        }

        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        Tracer tracer = tracerProvider.getIfAvailable();
        MeterRegistry meterRegistry = meterRegistryProvider.getIfAvailable();

        String traceId = (tracer != null && tracer.currentSpan() != null && tracer.currentSpan().context() != null)
                ? tracer.currentSpan().context().traceId()
                : (exchange.getAttribute(TraceIdWebFilter.TRACE_ID_ATTRIBUTE) != null)
                    ? exchange.getAttribute(TraceIdWebFilter.TRACE_ID_ATTRIBUTE).toString()
                    : "N/A";

        if (tracer != null && tracer.currentSpan() != null) {
            tracer.currentSpan().tag("error.code", code);
            tracer.currentSpan().tag("error.status", String.valueOf(status.value()));
        }

        if (meterRegistry != null) {
            meterRegistry.counter("auth_errors_total", "code", code, "status", String.valueOf(status.value())).increment();
        }

        ErrorResponse errorResponse = ErrorResponse.builder()
                .resultCode(code)
                .resultMsg(message)
                .traceId(traceId)
                .build();

        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(errorResponse);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize security error response: {}", e.getMessage(), e);
            bytes = ("{\"resultCode\":\"" + code + "\",\"resultMsg\":\"" + message + "\",\"traceId\":\"" + traceId + "\"}").getBytes(StandardCharsets.UTF_8);
        }

        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .headers(headers -> headers
                        .hsts(hsts -> hsts.maxAge(Duration.ofDays(365)).includeSubdomains(true))
                        .frameOptions(frameOptions -> frameOptions.mode(XFrameOptionsServerHttpHeadersWriter.Mode.DENY))
                        .contentTypeOptions(Customizer.withDefaults())
                        .xssProtection(Customizer.withDefaults())
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'; frame-ancestors 'none'; object-src 'none';"))
                )
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((exchange, ex) -> writeSecurityError(
                                exchange, HttpStatus.UNAUTHORIZED,
                                AuthResponseCode.AUTH_000401_UNAUTHORIZED.getCode(),
                                "Unauthorized access. Authentication is required."))
                        .accessDeniedHandler((exchange, ex) -> writeSecurityError(
                                exchange, HttpStatus.FORBIDDEN,
                                AuthResponseCode.AUTH_000403_ACCESS_DENIED.getCode(),
                                "Access denied. Insufficient permissions."))
                )
                .authorizeExchange((ServerHttpSecurity.AuthorizeExchangeSpec exchanges) -> {
                    ServerHttpSecurity.AuthorizeExchangeSpec.Access publicMatchers = exchanges
                            .pathMatchers(
                                    "/auths/register", "/auths/verify-email", "/auths/verify-email/**",
                                    "/auths/fp", "/auths/reset-password", "/auths/login", "/auths/refresh",
                                    "/auths/social/**", "/webhooks/**", "/api/users", "/actuator/**"
                            );
                    ServerHttpSecurity.AuthorizeExchangeSpec spec = publicMatchers.permitAll();

                    if (applicationProperties.getSpringdoc().getSwaggerUi().isEnabled()) {
                        spec = spec.pathMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**", "/webjars/**").permitAll();
                    } else {
                        spec = spec.pathMatchers("/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**", "/webjars/**").hasRole("ADMIN");
                    }

                    spec.pathMatchers("/internal/**", "/api/users/bulk", "/api/jobs/**").hasRole("ADMIN")
                            .anyExchange().authenticated();
                })
                .addFilterAt(jwtAuthenticationWebFilter, SecurityWebFiltersOrder.AUTHENTICATION)
                .build();
    }
}
