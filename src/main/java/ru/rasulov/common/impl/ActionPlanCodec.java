package ru.rasulov.common.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.rasulov.common.api.exception.InvalidRequestException;

import javax.inject.Named;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Canonical JSON payloads and fingerprints for preview/apply comparisons. */
@Named
public class ActionPlanCodec {
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private final ObjectMapper mapper = new ObjectMapper();

    public String request(Object value) { return json(value); }

    public String fingerprint(Object value) {
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(json(value).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
        char[] result = new char[digest.length * 2];
        char[] digits = HEX;
        for (int i = 0; i < digest.length; i++) {
            int byteValue = digest[i] & 0xff;
            result[i * 2] = digits[byteValue >>> 4];
            result[i * 2 + 1] = digits[byteValue & 0x0f];
        }
        return new String(result);
    }

    public <T> T mode(String json, boolean dryRun, Class<T> type) {
        try {
            JsonNode parsed = mapper.readTree(json);
            ((ObjectNode) parsed).put("dryRun", dryRun);
            ((ObjectNode) parsed).remove("planId");
            return mapper.treeToValue(parsed, type);
        } catch (IOException | RuntimeException e) {
            throw new InvalidRequestException("The saved preview cannot be read. Run preview again.");
        }
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalStateException("Could not serialize an action plan", e);
        }
    }
}
