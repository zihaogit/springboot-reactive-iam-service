package com.example.springbootbasiclogin.config;

import brave.handler.MutableSpan;
import io.micrometer.observation.ObservationPredicate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext;
import zipkin2.reporter.BytesEncoder;
import zipkin2.reporter.BytesMessageSender;
import zipkin2.reporter.brave.MutableSpanBytesEncoder;
import zipkin2.reporter.urlconnection.URLConnectionSender;

@Configuration
public class ZipkinConfig {

    @Bean
    @ConditionalOnMissingBean
    public BytesMessageSender zipkinSender(
            @Value("${management.zipkin.tracing.endpoint:http://localhost:9411/api/v2/spans}") String endpoint) {
        return URLConnectionSender.create(endpoint);
    }

    @Bean
    @ConditionalOnMissingBean
    public BytesEncoder<MutableSpan> mutableSpanBytesEncoder() {
        return MutableSpanBytesEncoder.JSON_V2;
    }

    @Bean
    public ObservationPredicate noActuatorObservationPredicate() {
        return (name, context) -> {
            if (context instanceof ServerRequestObservationContext serverContext) {
                return !serverContext.getCarrier().getPath().value().startsWith("/actuator");
            }
            return true;
        };
    }
}
