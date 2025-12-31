package org.openhab.binding.rainbird.internal.net;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetSocketAddress;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openhab.binding.rainbird.internal.config.RainbirdConfiguration;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

@NonNullByDefault
class RainbirdClientTest {

    private @Nullable HttpServer server;
    private @Nullable MockRainbirdClient client;
    private final Deque<Map<String, @Nullable Object>> responses = new ArrayDeque<>();

    @BeforeEach
    void setUp() throws IOException {
        HttpServer serverLocal = HttpServer.create(new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0),
                0);
        serverLocal.createContext("/stick", this::handleRequest);
        serverLocal.start();
        server = serverLocal;

        RainbirdConfiguration config = new RainbirdConfiguration();
        config.host = Objects.requireNonNull(serverLocal.getAddress().getHostString());
        config.port = serverLocal.getAddress().getPort();
        config.password = "testpassword";

        client = new MockRainbirdClient(config);
    }

    @AfterEach
    void tearDown() {
        HttpServer serverLocal = server;
        if (serverLocal != null) {
            serverLocal.stop(0);
        }
    }

    @Test
    @SuppressWarnings("null")
    void testGetRecordedParams() throws IOException, InterruptedException {
        MockRainbirdClient clientLocal = client;
        if (clientLocal == null) {
            return;
        }
        Map<String, @Nullable Object> response1 = new LinkedHashMap<>();
        response1.put("data", "3801");
        responses.add(response1);

        Map<String, @Nullable Object> response2 = new LinkedHashMap<>();
        response2.put("data", "3900050A");
        responses.add(response2);

        Map<String, @Nullable Object> response3 = new LinkedHashMap<>();
        response3.put("data", "40");
        responses.add(response3);

        Map<String, @Nullable Object> params = new LinkedHashMap<>();
        params.put("data", "02");
        params.put("length", Objects.requireNonNull(Integer.valueOf(1)));
        clientLocal.invoke("tunnelSip", params);
        clientLocal.invoke("tunnelSip", params);
        clientLocal.invoke("tunnelSip", params);

        List<Map<String, @Nullable Object>> recorded = clientLocal.getRecordedParams();
        assertEquals(3, recorded.size());
        assertEquals("02", Objects.requireNonNull(recorded.get(0).get("data")));
        assertEquals("02", Objects.requireNonNull(recorded.get(1).get("data")));
        assertEquals("02", Objects.requireNonNull(recorded.get(2).get("data")));
    }

    private void handleRequest(@Nullable HttpExchange exchange) throws IOException {
        if (exchange == null) {
            return;
        }
        byte[] body = Objects.requireNonNull(exchange.getRequestBody().readAllBytes());

        RainbirdPayloadCoder coder = new RainbirdPayloadCoder("testpassword");
        Map<String, @Nullable Object> payload = coder.decode(body);

        Map<String, @Nullable Object> responseBody = Objects.requireNonNull(responses.pollFirst());
        Map<String, @Nullable Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", Objects.requireNonNull(payload.get("id")));
        envelope.put("result", responseBody);

        byte[] bytes = coder.encode(envelope);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private class MockRainbirdClient extends RainbirdClient {
        private final List<Map<String, @Nullable Object>> recordedParams = new ArrayList<>();

        MockRainbirdClient(RainbirdConfiguration configuration) {
            super(configuration);
        }

        @Override
        public Map<String, @Nullable Object> invoke(String method, Map<String, @Nullable Object> params)
                throws IOException, InterruptedException {
            recordedParams.add(new LinkedHashMap<>(params));
            return super.invoke(method, params);
        }

        List<Map<String, @Nullable Object>> getRecordedParams() {
            return recordedParams;
        }
    }
}
