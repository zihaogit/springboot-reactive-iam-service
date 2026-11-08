package com.example.springbootbasiclogin.exception;

import com.example.springbootbasiclogin.config.TraceIdWebFilter;
import com.example.springbootbasiclogin.constant.AuthResponseCode;
import com.example.springbootbasiclogin.dto.exception.ErrorResponse;
import com.example.springbootbasiclogin.util.MessageUtil;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    private final MessageUtil messageUtil;
    private final Tracer tracer;
    private final MeterRegistry meterRegistry;

    public GlobalExceptionHandler(MessageUtil messageUtil,
                                  ObjectProvider<Tracer> tracerProvider,
                                  ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.messageUtil = messageUtil;
        this.tracer = tracerProvider.getIfAvailable();
        this.meterRegistry = meterRegistryProvider.getIfAvailable();
    }

    private String getTraceId(ServerWebExchange exchange) {
        if (tracer != null) {
            Span currentSpan = tracer.currentSpan();
            if (currentSpan != null && currentSpan.context() != null) {
                return currentSpan.context().traceId();
            }
        }
        if (exchange != null) {
            Object attr = exchange.getAttribute(TraceIdWebFilter.TRACE_ID_ATTRIBUTE);
            if (attr instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return "N/A";
    }

    private void recordErrorMetricsAndTags(String errorCode, String httpStatus, Throwable ex) {
        if (tracer != null) {
            Span currentSpan = tracer.currentSpan();
            if (currentSpan != null) {
                currentSpan.tag("error.code", errorCode);
                currentSpan.tag("error.status", httpStatus);
                if (ex != null) {
                    currentSpan.error(ex);
                }
            }
        }

        if (meterRegistry != null) {
            meterRegistry.counter("auth_errors_total",
                    "code", errorCode,
                    "status", httpStatus
            ).increment();
        }
    }

    @ExceptionHandler({org.springframework.web.server.ServerWebInputException.class, org.springframework.web.bind.support.WebExchangeBindException.class})
    public Mono<ResponseEntity<ErrorResponse>> handleBadRequestException(Exception ex, ServerWebExchange exchange) {
        String traceId = getTraceId(exchange);
        String code = AuthResponseCode.AUTH_000101_INVALID_OR_MISSING_PARAMETER.getCode();
        recordErrorMetricsAndTags(code, "400", ex);

        log.warn("[traceId={}] Bad request input validation error: {}", traceId, ex.getMessage());

        String message = messageUtil.getMessage(AuthResponseCode.AUTH_000101_INVALID_OR_MISSING_PARAMETER.getMessageKey());
        ErrorResponse response = ErrorResponse.builder()
                .resultCode(code)
                .resultMsg(message != null ? message : "Invalid or missing parameter.")
                .traceId(traceId)
                .build();

        return Mono.just(ResponseEntity
                .status(AuthResponseCode.AUTH_000101_INVALID_OR_MISSING_PARAMETER.getHttpStatus())
                .body(response));
    }

    @ExceptionHandler(Exception.class)
    public Mono<ResponseEntity<ErrorResponse>> handleMissedException(Exception ex, ServerWebExchange exchange) {
        String traceId = getTraceId(exchange);
        String code = AuthResponseCode.AUTH_000500_SERVER_ERROR.getCode();
        recordErrorMetricsAndTags(code, "500", ex);

        log.error("[traceId={}] Unhandled internal server exception: ", traceId, ex);

        String message = messageUtil.getMessage(AuthResponseCode.AUTH_000500_SERVER_ERROR.getMessageKey());
        ErrorResponse response = ErrorResponse.builder()
                .resultCode(code)
                .resultMsg(message != null ? message : "An unexpected internal server error occurred. Please contact support.")
                .traceId(traceId)
                .build();

        return Mono.just(ResponseEntity
                .status(AuthResponseCode.AUTH_000500_SERVER_ERROR.getHttpStatus())
                .body(response));
    }

    @ExceptionHandler(value = {CustomException.class})
    public Mono<ResponseEntity<ErrorResponse>> handleCustomException(CustomException ex, ServerWebExchange exchange) {
        String traceId = getTraceId(exchange);
        String code = ex.getAuthResponseCode().getCode();
        String httpStatus = String.valueOf(ex.getAuthResponseCode().getHttpStatus().value());
        recordErrorMetricsAndTags(code, httpStatus, ex);

        if (ex.getAuthResponseCode().getHttpStatus().is5xxServerError()) {
            log.error("[traceId={}] Server error handling request: [{}] {}", traceId, code, ex.getMessage(), ex);
        } else {
            log.warn("[traceId={}] Client error: [{}] {}", traceId, code, ex.getMessage());
        }

        // Choose message based on presence of messageArgs
        String message = (ex.getMessageArgs() != null && ex.getMessageArgs().length > 0)
                ? messageUtil.getMessageWithArgs(ex.getAuthResponseCode().getMessageKey(), ex.getMessageArgs())
                : messageUtil.getMessage(ex.getAuthResponseCode().getMessageKey());

        ErrorResponse response = ErrorResponse.builder()
                .resultCode(code)
                .resultMsg(message != null ? message : ex.getMessage())
                .traceId(traceId)
                .build();

        return Mono.just(ResponseEntity
                .status(ex.getAuthResponseCode().getHttpStatus())
                .body(response));
    }
}
