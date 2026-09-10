package com.financial.copilot.agent.tools.http;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Fluent builder for constructing HTTP requests with WebClient.
 * Supports method, path, headers and optional JSON body.
 */
public final class HttpRequestBuilder {

    private final WebClient client;
    private HttpMethod method = HttpMethod.GET;
    private String path = "";
    private Object body;
    private final HttpHeaders headers = new HttpHeaders();

    private HttpRequestBuilder(WebClient client) {
        this.client = client;
    }

    public static HttpRequestBuilder using(WebClient client) {
        return new HttpRequestBuilder(client);
    }

    public HttpRequestBuilder method(HttpMethod method) {
        this.method = method;
        return this;
    }

    public HttpRequestBuilder path(String path) {
        this.path = path;
        return this;
    }

    public HttpRequestBuilder header(String key, String value) {
        this.headers.add(key, value);
        return this;
    }

    public HttpRequestBuilder body(Object body) {
        this.body = body;
        return this;
    }

    public <T> Mono<T> retrieve(Class<T> responseType) {
        return client.method(method)
                .uri(path)
                .headers(httpHeaders -> httpHeaders.addAll(headers))
                .body(body == null ? BodyInserters.empty() : BodyInserters.fromValue(body))
                .retrieve()
                .onStatus(httpStatus -> httpStatus.isError(), response ->
                        response.bodyToMono(String.class)
                                .flatMap(errorBody -> Mono.error(new RuntimeException(errorBody))))
                .bodyToMono(responseType);
    }
}
