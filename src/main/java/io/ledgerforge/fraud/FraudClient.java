package io.ledgerforge.fraud;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

/**
 * Thin HTTP client for fraud-service. Short timeouts: a slow fraud check must not stall payments.
 *
 * With an SSL bundle configured the connection is mutual TLS: the ledger presents its client certificate
 * and accepts only a server certificate signed by the bundle's CA, so neither side can be impersonated.
 */
public class FraudClient {

    private final RestClient http;

    /** @param builder Spring's auto-configured builder, so calls are traced and timed */
    public FraudClient(FraudProperties props, RestClient.Builder builder, SslBundles sslBundles) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(props.connectTimeout())
                .withReadTimeout(props.readTimeout());
        if (props.sslBundle() != null && !props.sslBundle().isBlank()) {
            if (!props.url().startsWith("https://")) {
                throw new IllegalStateException("ledgerforge.plugins.fraud.ssl-bundle is set, so the fraud service "
                        + "url must use https:// (mutual TLS is never silently downgraded)");
            }
            settings = settings.withSslBundle(sslBundles.getBundle(props.sslBundle()));
        }
        this.http = builder
                .baseUrl(props.url())
                .requestFactory(ClientHttpRequestFactoryBuilder.jdk()
                        .withHttpClientCustomizer(client -> client.version(HttpClient.Version.HTTP_1_1))
                        .build(settings))
                .defaultHeader("X-Service-Token", props.token())
                .build();
    }

    /** Throws a RestClientException on timeouts, TLS failures, connection failures and non-2xx answers. */
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
