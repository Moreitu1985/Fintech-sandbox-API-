package io.ledgerforge.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Minimal client for the Claude Messages API (POST /v1/messages) with tool use.
 * See https://platform.claude.com/docs/en/agents-and-tools/tool-use/overview
 */
public class AnthropicClient {

    static final String API_VERSION = "2023-06-01";

    private final RestClient http;

    public AnthropicClient(AssistantProperties props) {
        if (props.apiKey() == null || props.apiKey().isBlank()) {
            throw new IllegalStateException("ledgerforge.assistant.api-key (ANTHROPIC_API_KEY) is required "
                    + "when the assistant is enabled");
        }
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.connectTimeout());
        factory.setReadTimeout(props.readTimeout());
        this.http = RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(factory)
                .defaultHeader("x-api-key", props.apiKey())
                .defaultHeader("anthropic-version", API_VERSION)
                .build();
    }

    /** Sends one Messages API request. Throws a RestClientException on timeouts and non-2xx answers. */
    public JsonNode createMessage(ObjectNode request) {
        JsonNode response = http.post()
                .uri("/v1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(JsonNode.class);
        if (response == null || !response.path("content").isArray()) {
            throw new IllegalStateException("Claude API returned an unexpected response");
        }
        return response;
    }
}
