package io.ledgerforge.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Scripted stand-in for the Claude Messages API. Each test queues the responses "Claude" gives,
 * and inspects the requests the ledger sent, including the tool results (i.e. exactly what data
 * the model would have seen).
 */
final class FakeClaude implements AutoCloseable {

    static final String API_KEY = "test-anthropic-key";

    private final HttpServer server;
    private final ObjectMapper json = new ObjectMapper();
    private final Deque<String> script = new ArrayDeque<>();
    final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    final List<Map<String, String>> headers = new CopyOnWriteArrayList<>();
    volatile String repeatForever;   // when set, answered for every request (loop tests)
    volatile int forcedStatus;       // when non-zero, every request fails with this status

    FakeClaude() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/v1/messages", exchange -> {
            JsonNode body = json.readTree(exchange.getRequestBody().readAllBytes());
            requests.add(body);
            headers.add(Map.of(
                    "x-api-key", String.valueOf(exchange.getRequestHeaders().getFirst("x-api-key")),
                    "anthropic-version", String.valueOf(exchange.getRequestHeaders().getFirst("anthropic-version"))));
            int status;
            String reply;
            synchronized (script) {
                if (forcedStatus != 0) {
                    status = forcedStatus;
                    reply = "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"forced\"}}";
                } else if (repeatForever != null) {
                    status = 200;
                    reply = repeatForever;
                } else if (!script.isEmpty()) {
                    String next = script.pollFirst();
                    if (next.startsWith("STATUS:")) {          // scripted failure, e.g. "STATUS:529"
                        status = Integer.parseInt(next.substring(7));
                        reply = "{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"scripted\"}}";
                    } else {
                        status = 200;
                        reply = next;
                    }
                } else {
                    status = 200;
                    reply = text("(script exhausted)");
                }
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

    void reset() {
        synchronized (script) {
            script.clear();
        }
        requests.clear();
        headers.clear();
        repeatForever = null;
        forcedStatus = 0;
    }

    void then(String response) {
        synchronized (script) {
            script.addLast(response);
        }
    }

    static String toolUse(String id, String name, String inputJson) {
        return "{\"id\":\"msg_" + id + "\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-haiku-5-5\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"Let me check.\"},"
                + "{\"type\":\"tool_use\",\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"input\":" + inputJson + "}],"
                + "\"stop_reason\":\"tool_use\",\"usage\":{\"input_tokens\":10,\"output_tokens\":10}}";
    }

    static String text(String answer) {
        return stop("end_turn", answer);
    }

    static String stop(String stopReason, String answer) {
        return "{\"id\":\"msg_final\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-haiku-5-5\","
                + "\"content\":[{\"type\":\"text\",\"text\":" + quote(answer) + "}],"
                + "\"stop_reason\":\"" + stopReason + "\",\"usage\":{\"input_tokens\":10,\"output_tokens\":10}}";
    }

    /** One response asking for {@code count} parallel calls of the same tool. */
    static String parallelToolUses(String name, int count) {
        StringBuilder blocks = new StringBuilder();
        for (int i = 1; i <= count; i++) {
            blocks.append(i > 1 ? "," : "").append("{\"type\":\"tool_use\",\"id\":\"toolu_p").append(i)
                    .append("\",\"name\":\"").append(name).append("\",\"input\":{}}");
        }
        return "{\"id\":\"msg_par\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-haiku-5-5\","
                + "\"content\":[" + blocks + "],\"stop_reason\":\"tool_use\","
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":10}}";
    }

    /** The tool_result blocks the ledger sent in request number {@code index} (0-based). */
    List<JsonNode> toolResults(int index) {
        JsonNode messages = requests.get(index).get("messages");
        JsonNode last = messages.get(messages.size() - 1);
        return last.get("content").findParents("tool_use_id");
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
