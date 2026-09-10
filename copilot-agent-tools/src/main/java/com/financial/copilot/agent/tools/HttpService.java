package com.financial.copilot.agent.tools;

import com.financial.copilot.agent.tools.dto.FundInfoDTO;
import com.financial.copilot.agent.tools.dto.UserProfileDTO;
import com.financial.copilot.agent.tools.http.HttpRequestBuilder;
import com.financial.copilot.agent.tools.http.HttpResponseHandler;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.util.UUID;

/**
 * Centralised service exposing HTTP‑based tools to other agents.
 * Each method builds a request with {@link HttpRequestBuilder},
 * applies {@link HttpResponseHandler} for consistent error handling
 * and returns a reactive {@link Mono} of the desired DTO.
 */
@Component
public class HttpService {

    private final WebClient webClient;

    public HttpService(WebClient webClient) {
        this.webClient = webClient;
    }

    /**
     * Example: fetch a user profile from the user‑service.
     */
    public Mono<UserProfileDTO> fetchUserProfile(Long userId, String authToken) {
        return HttpResponseHandler.handle(
                HttpRequestBuilder.using(webClient)
                        .method(HttpMethod.GET)
                        .path("/user/profile/" + userId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + authToken)
                        .retrieve(UserProfileDTO.class)
        );
    }

    /**
     * Example: obtain fund information from the fund‑service.
     */
    public Mono<FundInfoDTO> getFundInfo(String fundCode) {
        return HttpResponseHandler.handle(
                HttpRequestBuilder.using(webClient)
                        .method(HttpMethod.GET)
                        .path("/fund/info?code=" + fundCode)
                        .header("X-Request-Id", UUID.randomUUID().toString())
                        .retrieve(FundInfoDTO.class)
        );
    }

    // Additional tool methods can be added here following the same pattern.
}
