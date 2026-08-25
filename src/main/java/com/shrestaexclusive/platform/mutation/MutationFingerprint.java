package com.shrestaexclusive.platform.mutation;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class MutationFingerprint {

    private MutationFingerprint() {
    }

    public static String json(ObjectMapper objectMapper, String method, String path, Object payload) {
        return hash(objectMapper, Map.of(
                "method", method,
                "path", path,
                "payload", payload == null ? Map.of() : payload
        ));
    }

    private static String hash(ObjectMapper objectMapper, Object value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] json = objectMapper.writeValueAsBytes(value);
            return HexFormat.of().formatHex(digest.digest(json));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Mutation request fingerprint cannot be serialized", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required for mutation fingerprints", exception);
        }
    }
}
