package org.openhab.binding.rainbird.internal.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the payload coder can decrypt captured /stick responses and re-encode them.
 */
class RainbirdPayloadCoderTest {

    private Map<String, Object> fixture;

    @BeforeEach
    void loadFixture() throws IOException {
        Path path = Path.of("src/test/resources/fixtures/stick_polling.json");
        String json = Files.readString(path);
        fixture = RainbirdJson.parseObject(json);
    }

    @Test
    void decodeCapturedResponses() throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> responses = (Map<String, Object>) fixture.get("responses");
        String password = (String) fixture.get("password");
        RainbirdPayloadCoder coder = new RainbirdPayloadCoder(password);

        Map<String, String> expectedPayloads = new HashMap<>();
        expectedPayloads.put("getNetworkStatus",
                "{\"id\":1,\"jsonrpc\":\"2.0\",\"result\":{\"networkUp\":true,\"internetUp\":true}}");
        expectedPayloads.put("getWifiParams",
                "{\"id\":2,\"jsonrpc\":\"2.0\",\"result\":{\"rssi\":-59,\"wifiSsid\":\"Garden\",\"macAddress\":\"AA:BB:CC:DD:EE:FF\"}}");
        expectedPayloads.put("getSettings",
                "{\"id\":3,\"jsonrpc\":\"2.0\",\"result\":{\"numPrograms\":3}}");
        expectedPayloads.put("tunnelSip_available",
                "{\"id\":4,\"jsonrpc\":\"2.0\",\"result\":{\"data\":\"83000000FFFF\"}}");
        expectedPayloads.put("tunnelSip_combined",
                "{\"id\":5,\"jsonrpc\":\"2.0\",\"result\":{\"data\":\"CC0A1E200977E80000000200FA001405\"}}");
        expectedPayloads.put("tunnelSip_ack",
                "{\"id\":6,\"jsonrpc\":\"2.0\",\"result\":{\"data\":\"0138\"}}");

        for (Map.Entry<String, Object> entry : responses.entrySet()) {
            String key = entry.getKey();
            String hex = (String) entry.getValue();
            byte[] payload = hexToBytes(hex);
            Map<String, Object> decoded = coder.decode(payload);
            String expectedJson = expectedPayloads.get(key);
            if (expectedJson == null) {
                throw new AssertionError("Missing expected payload for " + key);
            }
            Map<String, Object> expected = RainbirdJson.parseObject(expectedJson);
            assertEquals(expected, decoded, "Decoded payload did not match for " + key);

            byte[] reencoded = coder.encode(decoded);
            Map<String, Object> roundTrip = coder.decode(reencoded);
            assertEquals(decoded, roundTrip, "Round-trip encode/decode mismatch for " + key);
            assertNotEquals(hex, bytesToHex(reencoded), "Encoded payload should use unique IV for " + key);
        }
    }

    private static byte[] hexToBytes(String hex) {
        int length = hex.length();
        byte[] data = new byte[length / 2];
        for (int i = 0; i < length; i += 2) {
            data[i / 2] = (byte) Integer.parseInt(hex.substring(i, i + 2), 16);
        }
        return data;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            builder.append(String.format("%02x", Byte.valueOf(b)));
        }
        return builder.toString();
    }
}
