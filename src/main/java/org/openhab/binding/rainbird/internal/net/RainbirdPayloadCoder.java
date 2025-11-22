package org.openhab.binding.rainbird.internal.net;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Handles encryption/decryption of Rain Bird payloads.
 */
@NonNullByDefault
final class RainbirdPayloadCoder {

    private static final int BLOCK_SIZE = 16;

    private static final Logger LOGGER = LoggerFactory.getLogger(RainbirdPayloadCoder.class);

    private final @Nullable byte[] sessionKey;
    private final SecureRandom secureRandom = new SecureRandom();

    RainbirdPayloadCoder(@Nullable String password) {
        if (password != null && !password.isBlank()) {
            this.sessionKey = deriveSessionKey(password);
        } else {
            this.sessionKey = null;
        }
    }

    public byte[] encode(Map<String, Object> payload) throws IOException {
        String json = RainbirdJson.stringify(payload);
        if (sessionKey == null) {
            return json.getBytes(StandardCharsets.UTF_8);
        }
        return encrypt(json);
    }

    public Map<String, Object> decode(byte[] payload) throws IOException {
        String json;
        if (sessionKey == null) {
            json = new String(payload, StandardCharsets.UTF_8);
        } else {
            json = decrypt(payload);
        }
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Rain Bird decoded JSON payload: {}", json);
        }
        return RainbirdJson.parseObject(json);
    }

    private byte[] encrypt(String data) throws IOException {
        byte[] padded = addPadding((data + "\u0000\u0010").getBytes(StandardCharsets.UTF_8));
        byte[] secretKey = sessionKey;
        byte[] iv = new byte[BLOCK_SIZE];
        secureRandom.nextBytes(iv);
        byte[] encrypted = aes(secretKey, iv, padded, Cipher.ENCRYPT_MODE);
        byte[] hash = sha256(data.getBytes(StandardCharsets.UTF_8));
        ByteBuffer buffer = ByteBuffer.allocate(hash.length + iv.length + encrypted.length);
        buffer.put(hash);
        buffer.put(iv);
        buffer.put(encrypted);
        return buffer.array();
    }

    private String decrypt(byte[] payload) throws IOException {
        if (payload.length < 48) {
            throw new IOException("Encrypted payload too short");
        }
        byte[] iv = Arrays.copyOfRange(payload, 32, 48);
        byte[] encrypted = Arrays.copyOfRange(payload, 48, payload.length);
        byte[] secretKey = sessionKey;
        byte[] decrypted = aes(secretKey, iv, encrypted, Cipher.DECRYPT_MODE);
        String text = new String(decrypted, StandardCharsets.UTF_8);
        text = rstrip(text, '\u0010');
        text = rstrip(text, '\n');
        text = rstrip(text, '\u0000');
        text = rstripWhitespace(text);
        return text;
    }

    private static byte[] deriveSessionKey(String password) {
        byte[] keyBytes = password.getBytes(StandardCharsets.UTF_8);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(keyBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static byte[] addPadding(byte[] data) {
        int padding = (BLOCK_SIZE - (data.length % BLOCK_SIZE)) % BLOCK_SIZE;
        if (padding == 0) {
            return data;
        }
        byte[] result = Arrays.copyOf(data, data.length + padding);
        Arrays.fill(result, data.length, result.length, (byte) 0x10);
        return result;
    }

    private static byte[] sha256(byte[] data) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 not available", e);
        }
    }

    private static byte[] aes(byte[] key, byte[] iv, byte[] data, int mode) throws IOException {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(data);
        } catch (Exception e) {
            throw new IOException("Unable to process AES payload", e);
        }
    }

    private static String rstrip(String value, char character) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == character) {
            end--;
        }
        return value.substring(0, end);
    }

    private static String rstripWhitespace(String value) {
        int end = value.length();
        while (end > 0 && Character.isWhitespace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(0, end);
    }

    public static Map<String, Object> requestPayload(long id, String method, Map<String, Object> params)
            throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", id);
        payload.put("jsonrpc", "2.0");
        payload.put("method", method);
        payload.put("params", params);
        return payload;
    }
}
