package org.openhab.binding.rainbird.internal.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.openhab.binding.rainbird.internal.config.RainbirdConfiguration;
import org.openhab.binding.rainbird.internal.net.RainbirdClient.CombinedState;
import org.openhab.binding.rainbird.internal.net.RainbirdClient.ControllerStatus;
import org.openhab.binding.rainbird.internal.net.RainbirdClient.PollingResult;
import org.openhab.binding.rainbird.internal.net.RainbirdClient.ProgramStatus;
import org.openhab.binding.rainbird.internal.net.RainbirdClient.ZoneStatus;
import org.openhab.binding.rainbird.internal.net.RainbirdCommandResult;

/**
 * Verifies that the client produces controller state snapshots from captured responses.
 */
class RainbirdClientTest {

    private static final String PASSWORD = "testpass";

    @Test
    void fetchStateAggregatesResponses() throws IOException, InterruptedException {
        RainbirdConfiguration configuration = new RainbirdConfiguration();
        configuration.host = "127.0.0.1";
        configuration.password = PASSWORD;

        List<ExpectedCall> calls = new ArrayList<>();
        calls.add(ExpectedCall.of("getNetworkStatus", Map.of("networkUp", true, "internetUp", true)));
        calls.add(ExpectedCall.of("getWifiParams", Map.of("rssi", -59)));
        calls.add(ExpectedCall.of("getSettings", Map.of("numPrograms", 3)));
        calls.add(ExpectedCall.of("tunnelSip", Map.of("data", "83003F000000")));
        calls.add(ExpectedCall.of("tunnelSip", Map.of("data", "CC0A1E200977E80000000200FA001405")));

        List<String> scheduleResponses = List.of(
                "A0000000000400",
                "A000106A0601006401",
                "A000117F0300002D00",
                "A00012000300006400",
                "A0006000F0FFFFFFFFFFFF",
                "A000610168FFFFFFFFFFFF",
                "A00062FFFFFFFFFFFFFFFF",
                "A00080001900010000001400020000",
                "A00081000700030000001400040000",
                "A00082000A00060000000000000000");
        for (String response : scheduleResponses) {
            calls.add(ExpectedCall.of("tunnelSip", Map.of("data", response)));
        }

        StubRainbirdClient client = new StubRainbirdClient(configuration, calls);
        PollingResult result = client.poll();
        ControllerStatus controller = result.getControllerStatus();
        ProgramStatus programs = result.getProgramStatus();
        ZoneStatus zones = result.getZoneStatus();

        assertEquals(-59, controller.getWifiStatus().getRssi());
        assertEquals(6, zones.getAvailableZones().size());
        assertEquals(3, programs.getProgramCount());
        assertTrue(controller.getNetworkStatus().isNetworkUp());
        assertTrue(controller.getNetworkStatus().isInternetUp());
        CombinedState combined = controller.getCombinedState();
        assertEquals(250, combined.getSeasonalAdjust());
        assertEquals(20, combined.getRemainingRuntime());
        assertEquals(5, combined.getActiveStation());
        assertEquals(2024, combined.getControllerTime().getYear());
        List<String> summaries = programs.getSummaries();
        assertEquals(3, summaries.size());
        assertEquals("Program A: Starts 04:00; Zones 1=25m, 2=20m, 3=7m, 4=20m, 5=10m", summaries.get(0));
        assertEquals("Program B: Starts 06:00; Zones 1=1m, 2=2m, 3=3m, 4=4m, 5=6m", summaries.get(1));
        assertEquals("Program C: No starts; No zones", summaries.get(2));

        List<Map<String, Object>> recorded = client.getRecordedParams();
        Map<String, Object> firstSchedule = recorded.stream()
                .filter(entry -> "200000".equals(entry.get("data")))
                .findFirst()
                .orElseThrow();
        assertEquals("200000", firstSchedule.get("data"));
    }

    @Test
    void manualCommandsSendSipPayloads() throws IOException, InterruptedException {
        RainbirdConfiguration configuration = new RainbirdConfiguration();
        configuration.host = "127.0.0.1";
        configuration.password = PASSWORD;

        List<ExpectedCall> calls = new ArrayList<>();
        calls.add(ExpectedCall.of("tunnelSip", Map.of("data", "0138")));
        calls.add(ExpectedCall.of("tunnelSip", Map.of("data", "0139")));
        calls.add(ExpectedCall.of("tunnelSip", Map.of("data", "0140")));

        StubRainbirdClient client = new StubRainbirdClient(configuration, calls);
        assertTrue(client.runProgram(1).isSuccess());
        assertTrue(client.runZones(List.of(5), 10).isSuccess());
        assertTrue(client.stopAllZones().isSuccess());

        RainbirdCommandResult invalidProgram = client.runProgram(-1);
        assertFalse(invalidProgram.isSuccess());

        List<Map<String, Object>> recorded = client.getRecordedParams();
        assertEquals("3801", recorded.get(0).get("data"));
        assertEquals("3900050A", recorded.get(1).get("data"));
        assertEquals("40", recorded.get(2).get("data"));
    }

    @Test
    void pollUsesKeepAliveConnectionHeader() throws Exception {
        RainbirdConfiguration configuration = new RainbirdConfiguration();
        configuration.host = "127.0.0.1";
        configuration.port = 0;
        configuration.basePath = "/stick";
        configuration.password = null;
        Deque<Map<String, Object>> responses = new ArrayDeque<>();
        responses.add(Map.of("networkUp", Boolean.TRUE, "internetUp", Boolean.TRUE));
        responses.add(Map.of("rssi", Integer.valueOf(-42)));
        responses.add(Map.of("numPrograms", Integer.valueOf(0)));
        responses.add(Map.of("data", "8300"));
        responses.add(Map.of("data", "CC0A1E200977E80000000200FA001405"));
        responses.add(Map.of("data", "A0000000000400"));

        HttpServer server = HttpServer.create(new InetSocketAddress(configuration.host, configuration.port), 0);
        List<String> connectionHeaders = new ArrayList<>();
        server.createContext(configuration.basePath, exchange -> handleRequest(exchange, responses, connectionHeaders));
        server.start();
        try {
            configuration.port = server.getAddress().getPort();
            RainbirdClient client = new RainbirdClient(configuration);
            PollingResult result = client.poll();
            assertTrue(connectionHeaders.stream().allMatch(value -> "keep-alive".equalsIgnoreCase(value)));
            assertEquals(0, responses.size());
            assertTrue(result.getControllerStatus().getNetworkStatus().isNetworkUp());
        } finally {
            server.stop(0);
        }
    }

    private void handleRequest(HttpExchange exchange, Deque<Map<String, Object>> responses, List<String> connectionHeaders)
            throws IOException {
        connectionHeaders.add(exchange.getRequestHeaders().getFirst("Connection"));
        if (!"keep-alive".equalsIgnoreCase(exchange.getRequestHeaders().getFirst("Connection"))) {
            exchange.sendResponseHeaders(400, -1);
            exchange.close();
            return;
        }
        byte[] body = exchange.getRequestBody().readAllBytes();
        Map<String, Object> payload = RainbirdJson.parseObject(new String(body, StandardCharsets.UTF_8));
        Map<String, Object> responseBody = responses.pollFirst();
        if (responseBody == null) {
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
            return;
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", payload.get("id"));
        envelope.put("result", responseBody);
        byte[] data = RainbirdJson.stringify(envelope).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, data.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(data);
        } finally {
            exchange.close();
        }
    }

    private static final class StubRainbirdClient extends RainbirdClient {

        private final List<ExpectedCall> responses;
        private final List<Map<String, Object>> recordedParams = new ArrayList<>();

        StubRainbirdClient(RainbirdConfiguration configuration, List<ExpectedCall> responses) {
            super(configuration);
            this.responses = new ArrayList<>(responses);
        }

        @Override
        protected Map<String, Object> invoke(String method, Map<String, Object> params)
                throws IOException, InterruptedException {
            if (responses.isEmpty()) {
                throw new IOException("Unexpected method call: " + method);
            }
            ExpectedCall call = responses.remove(0);
            if (!call.method.equals(method)) {
                throw new IOException("Unexpected method " + method + ", expected " + call.method);
            }
            recordedParams.add(new LinkedHashMap<>(params));
            return call.result;
        }

        List<Map<String, Object>> getRecordedParams() {
            return recordedParams;
        }
    }

    private static final class ExpectedCall {

        private final String method;
        private final Map<String, Object> result;

        private ExpectedCall(String method, Map<String, Object> result) {
            this.method = method;
            this.result = result;
        }

        static ExpectedCall of(String method, Map<String, Object> result) {
            return new ExpectedCall(method, result);
        }
    }
}
