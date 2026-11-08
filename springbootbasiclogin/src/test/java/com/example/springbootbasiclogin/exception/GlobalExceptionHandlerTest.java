package com.example.springbootbasiclogin.exception;

import com.example.springbootbasiclogin.constant.AuthResponseCode;
import com.example.springbootbasiclogin.dto.exception.ErrorResponse;
import com.example.springbootbasiclogin.util.MessageUtil;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ServerWebInputException;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {

    @Mock
    private MessageUtil messageUtil;

    @Mock
    private Tracer tracer;

    @Mock
    private Span span;

    @Mock
    private TraceContext traceContext;

    @Mock
    private ObjectProvider<Tracer> tracerProvider;

    @Mock
    private ObjectProvider<MeterRegistry> meterRegistryProvider;

    private MeterRegistry meterRegistry;
    private GlobalExceptionHandler exceptionHandler;

    private final String expectedTraceId = "4bf92f3577b34da6a3ce929d0e0e4736";

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();

        when(tracerProvider.getIfAvailable()).thenReturn(tracer);
        when(meterRegistryProvider.getIfAvailable()).thenReturn(meterRegistry);

        lenient().when(tracer.currentSpan()).thenReturn(span);
        lenient().when(span.context()).thenReturn(traceContext);
        lenient().when(traceContext.traceId()).thenReturn(expectedTraceId);

        exceptionHandler = new GlobalExceptionHandler(messageUtil, tracerProvider, meterRegistryProvider);
    }

    @Test
    @DisplayName("Should capture custom exception, populate traceId, set span tags and increment Prometheus metric")
    void shouldHandleCustomExceptionWithTraceIdAndMetrics() {
        CustomException ex = new CustomException(AuthResponseCode.AUTH_000104_INVALID_AUTHENTICATION);
        when(messageUtil.getMessage(AuthResponseCode.AUTH_000104_INVALID_AUTHENTICATION.getMessageKey()))
                .thenReturn("Invalid username or password.");

        StepVerifier.create(exceptionHandler.handleCustomException(ex, null))
                .assertNext(responseEntity -> {
                    assertEquals(HttpStatus.BAD_REQUEST, responseEntity.getStatusCode());
                    ErrorResponse body = responseEntity.getBody();
                    assertNotNull(body);
                    assertEquals(AuthResponseCode.AUTH_000104_INVALID_AUTHENTICATION.getCode(), body.getResultCode());
                    assertEquals("Invalid username or password.", body.getResultMsg());
                    assertEquals(expectedTraceId, body.getTraceId());
                })
                .verifyComplete();

        // Verify tracing tag was attached to active span
        verify(span).tag("error.code", AuthResponseCode.AUTH_000104_INVALID_AUTHENTICATION.getCode());
        verify(span).tag("error.status", "400");
        verify(span).error(ex);

        // Verify Prometheus metric was incremented
        Counter counter = meterRegistry.find("auth_errors_total")
                .tag("code", AuthResponseCode.AUTH_000104_INVALID_AUTHENTICATION.getCode())
                .tag("status", "400")
                .counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count());
    }

    @Test
    @DisplayName("Should capture bad request validation exception with traceId and metric")
    void shouldHandleBadRequestExceptionWithTraceIdAndMetrics() {
        ServerWebInputException ex = new ServerWebInputException("Missing required parameter: email");
        when(messageUtil.getMessage(AuthResponseCode.AUTH_000101_INVALID_OR_MISSING_PARAMETER.getMessageKey()))
                .thenReturn("Invalid or missing parameter.");

        StepVerifier.create(exceptionHandler.handleBadRequestException(ex, null))
                .assertNext(responseEntity -> {
                    assertEquals(HttpStatus.BAD_REQUEST, responseEntity.getStatusCode());
                    ErrorResponse body = responseEntity.getBody();
                    assertNotNull(body);
                    assertEquals(AuthResponseCode.AUTH_000101_INVALID_OR_MISSING_PARAMETER.getCode(), body.getResultCode());
                    assertEquals("Invalid or missing parameter.", body.getResultMsg());
                    assertEquals(expectedTraceId, body.getTraceId());
                })
                .verifyComplete();

        Counter counter = meterRegistry.find("auth_errors_total")
                .tag("code", AuthResponseCode.AUTH_000101_INVALID_OR_MISSING_PARAMETER.getCode())
                .tag("status", "400")
                .counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count());
    }

    @Test
    @DisplayName("Should capture unhandled internal server error (500) with traceId and metric")
    void shouldHandleMissedExceptionWithTraceIdAndMetrics() {
        RuntimeException ex = new RuntimeException("Database connection timeout");
        when(messageUtil.getMessage(AuthResponseCode.AUTH_000500_SERVER_ERROR.getMessageKey()))
                .thenReturn("An unexpected internal server error occurred.");

        StepVerifier.create(exceptionHandler.handleMissedException(ex, null))
                .assertNext(responseEntity -> {
                    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, responseEntity.getStatusCode());
                    ErrorResponse body = responseEntity.getBody();
                    assertNotNull(body);
                    assertEquals(AuthResponseCode.AUTH_000500_SERVER_ERROR.getCode(), body.getResultCode());
                    assertEquals("An unexpected internal server error occurred.", body.getResultMsg());
                    assertEquals(expectedTraceId, body.getTraceId());
                })
                .verifyComplete();

        Counter counter = meterRegistry.find("auth_errors_total")
                .tag("code", AuthResponseCode.AUTH_000500_SERVER_ERROR.getCode())
                .tag("status", "500")
                .counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count());
    }
}

