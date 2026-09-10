package com.financial.copilot.agent.tools.http;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Centralised response handling for HTTP calls.
 * Currently it only logs errors, but can be extended with
 * retry, circuit‑breaker or custom exception mapping.
 */
public final class HttpResponseHandler {

    private static final Logger log = LoggerFactory.getLogger(HttpResponseHandler.class);

    private HttpResponseHandler() {}

    public static <T> Mono<T> handle(Mono<T> upstream) {
        return upstream.doOnError(e -> log.warn("HTTP request failed: {}", e.getMessage()));
    }
}
