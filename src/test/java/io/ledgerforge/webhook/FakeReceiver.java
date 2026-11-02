package io.ledgerforge.webhook;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/** A local HTTP server standing in for a tenant's webhook receiver. Answers with scripted status codes. */
final class FakeReceiver implements AutoCloseable {

    record Received(Map<String, List<String>> headers, String body) {
        String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                    .findFirst().map(e -> e.getValue().getFirst()).orElse(null);
        }
    }

    final List<Received> requests = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedQueue<Integer> script = new ConcurrentLinkedQueue<>();
    private final AtomicInteger defaultStatus = new AtomicInteger(200);
    private final AtomicInteger delayMillis = new AtomicInteger(0);
    private final HttpServer server;

    FakeReceiver() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                requests.add(new Received(Map.copyOf(exchange.getRequestHeaders()),
                        new String(in.readAllBytes(), StandardCharsets.UTF_8)));
            }
            int delay = delayMillis.get();
            if (delay > 0) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            Integer next = script.poll();
            exchange.sendResponseHeaders(next != null ? next : defaultStatus.get(), -1);
            exchange.close();
        });
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }

    /** The next responses, in order; afterwards the default status. */
    void respondWith(Integer... statuses) {
        script.addAll(List.of(statuses));
    }

    /** Answer every request this much later (a slow receiver). */
    void delay(Duration delay) {
        delayMillis.set((int) delay.toMillis());
    }

    void respondAlways(int status) {
        defaultStatus.set(status);
    }

    List<Received> await(Predicate<List<Received>> condition, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.test(requests)) {
                return requests;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Receiver condition not met within " + timeout + "; got " + requests.size()
                + " request(s)");
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
