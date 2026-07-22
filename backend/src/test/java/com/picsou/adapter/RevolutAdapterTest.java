package com.picsou.adapter;

import com.picsou.exception.SyncException;
import com.picsou.service.sync.SyncProgressService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class RevolutAdapterTest {

    @Test
    void sync_sendsAllowLoginFalseToSidecar() throws Exception {
        List<String> bodies = new CopyOnWriteArrayList<>();
        try (TestServer server = TestServer.start(exchange -> {
            bodies.add(readBody(exchange));
            respond(exchange, 200, "{\"accounts\":[]}");
        })) {
            RevolutAdapter adapter = new RevolutAdapter(server.baseUrl(), mock(SyncProgressService.class));

            adapter.sync("+33600000000", "123456", 5L, false);

            assertThat(bodies).hasSize(1);
            assertThat(bodies.getFirst()).contains("\"allowLogin\":false");
        }
    }

    @Test
    void sync_mapsBrowserLaunchFailureToSyncException() throws Exception {
        try (TestServer server = TestServer.start(exchange ->
            respond(exchange, 503, "{\"error\":\"BROWSER_LAUNCH_FAILED\"}"))) {
            RevolutAdapter adapter = new RevolutAdapter(server.baseUrl(), mock(SyncProgressService.class));

            assertThatThrownBy(() -> adapter.sync("+33600000000", "123456", 5L))
                .isInstanceOf(SyncException.class)
                .hasMessage("BROWSER_LAUNCH_FAILED");
        }
    }

    private static String readBody(HttpExchange exchange) {
        try {
            return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) {
        try {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private interface Handler {
        void handle(HttpExchange exchange);
    }

    private static class TestServer implements AutoCloseable {
        private final HttpServer server;

        private TestServer(HttpServer server) {
            this.server = server;
        }

        static TestServer start(Handler handler) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/sync", handler::handle);
            server.createContext("/progress/5", exchange -> respond(exchange, 200, "{\"phase\":null}"));
            server.start();
            return new TestServer(server);
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
