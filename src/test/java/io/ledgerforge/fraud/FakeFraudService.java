package io.ledgerforge.fraud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Minimal stand-in for fraud-service: decides by amount and records every request it receives.
 * amount >= 9000 -> BLOCK, >= 5000 -> REVIEW, otherwise ALLOW. Can simulate an outage.
 */
final class FakeFraudService implements AutoCloseable {

    static final String TOKEN = "fake-fraud-service-token-0123456789abcdef";

    private final HttpServer server;
    private final ObjectMapper json = new ObjectMapper();
    final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    volatile boolean down;

    FakeFraudService() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/score", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            String reply;
            int status;
            if (down) {
                status = 503;
                reply = "{\"detail\":\"down\"}";
            } else if (!TOKEN.equals(exchange.getRequestHeaders().getFirst("X-Service-Token"))) {
                status = 401;
                reply = "{\"detail\":\"bad token\"}";
            } else {
                JsonNode request = json.readTree(body);
                requests.add(request);
                double amount = request.get("features").get("amount").asDouble();
                String decision = amount >= 9000 ? "BLOCK" : amount >= 5000 ? "REVIEW" : "ALLOW";
                status = 200;
                reply = "{\"score\":" + (decision.equals("BLOCK") ? 0.99 : decision.equals("REVIEW") ? 0.5 : 0.01)
                        + ",\"decision\":\"" + decision + "\",\"reasons\":"
                        + (decision.equals("ALLOW") ? "[]"
                        : "[{\"feature\":\"amount\",\"contribution\":2.5,\"message\":\"Large amount\"}]")
                        + ",\"model_version\":\"fake-1\"}";
            }
            byte[] out = reply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    JsonNode lastFeatures() {
        return requests.get(requests.size() - 1).get("features");
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
