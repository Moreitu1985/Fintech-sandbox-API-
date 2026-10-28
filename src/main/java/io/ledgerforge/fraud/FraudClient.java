package io.ledgerforge.fraud;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Thin HTTP client for fraud-service. Short timeouts: a slow fraud check must not stall payments. */
public class FraudClient {

    private final RestClient http;

    public FraudClient(FraudProperties props) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.connectTimeout());
        factory.setReadTimeout(props.readTimeout());
        this.http = RestClient.builder()
                .baseUrl(props.url())
                .requestFactory(factory)
                .defaultHeader("X-Service-Token", props.token())
                .build();
    }

    /** Throws a RestClientException on timeouts, connection failures and non-2xx answers. */
    public FraudModels.ScoreResponse score(FraudModels.ScoreRequest request) {
        FraudModels.ScoreResponse response = http.post()
                .uri("/v1/score")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(FraudModels.ScoreResponse.class);
        if (response == null || response.decision() == null) {
            throw new IllegalStateException("fraud-service returned an empty response");
        }
        return response;
    }
}
