package com.example.springbootbasiclogin.config;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
@RequiredArgsConstructor
public class TraceIdWebFilter implements WebFilter {

    public static final String TRACE_ID_ATTRIBUTE = "traceId";
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    private final ObjectProvider<Tracer> tracerProvider;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (path.startsWith("/actuator")) {
            return chain.filter(exchange);
        }

        Tracer tracer = tracerProvider.getIfAvailable();
        Span span = tracer != null ? tracer.currentSpan() : null;
        boolean customSpan = false;

        if (span == null && tracer != null) {
            span = tracer.nextSpan()
                    .name(exchange.getRequest().getMethod().name() + " " + exchange.getRequest().getPath().value())
                    .start();
            customSpan = true;
        }

        String traceId = null;
        if (span != null && span.context() != null) {
            traceId = span.context().traceId();
        }

        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }

        exchange.getAttributes().put(TRACE_ID_ATTRIBUTE, traceId);
        exchange.getResponse().getHeaders().set(TRACE_ID_HEADER, traceId);

        final Span activeSpan = span;
        final boolean isCustom = customSpan;

        Mono<Void> execution = chain.filter(exchange);

        if (activeSpan != null && isCustom) {
            return execution
                    .doOnError(err -> {
                        activeSpan.error(err);
                        activeSpan.tag("error", err.getMessage() != null ? err.getMessage() : err.getClass().getSimpleName());
                    })
                    .doFinally(signalType -> activeSpan.end());
        }

        return execution;
    }
}
