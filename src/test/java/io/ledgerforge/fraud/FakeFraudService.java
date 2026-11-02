package io.ledgerforge.fraud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import java.security.cert.X509Certificate;

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
    /** When non-zero, answer every request with this HTTP status (e.g. 401 to simulate a token mismatch). */
    volatile int forcedStatus;

    final List<String> clientCertificateSubjects = new CopyOnWriteArrayList<>();
    private final boolean tls;

    FakeFraudService() throws IOException {
        this(null);
    }

    /** With an SSLContext: HTTPS that REQUIRES a client certificate trusted by that context (mutual TLS). */
    FakeFraudService(SSLContext sslContext) throws IOException {
        this.tls = sslContext != null;
        if (sslContext == null) {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } else {
            HttpsServer https = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            https.setHttpsConfigurator(new HttpsConfigurator(sslContext) {
                @Override
                public void configure(HttpsParameters params) {
                    SSLParameters ssl = sslContext.getDefaultSSLParameters();
                    ssl.setNeedClientAuth(true);
                    params.setSSLParameters(ssl);
                }
            });
            server = https;
        }
        server.createContext("/v1/score", exchange -> {
            if (exchange instanceof HttpsExchange secure) {
                try {
                    var peer = (X509Certificate) secure.getSSLSession().getPeerCertificates()[0];
                    clientCertificateSubjects.add(peer.getSubjectX500Principal().getName());
                } catch (SSLPeerUnverifiedException e) {
                    clientCertificateSubjects.add("none");
                }
            }
            byte[] body = exchange.getRequestBody().readAllBytes();
            String reply;
            int status;
            if (forcedStatus != 0) {
                status = forcedStatus;
                reply = "{\"detail\":\"forced\"}";
            } else if (down) {
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
        return (tls ? "https" : "http") + "://127.0.0.1:" + server.getAddress().getPort();
    }

    JsonNode lastFeatures() {
        return requests.get(requests.size() - 1).get("features");
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
