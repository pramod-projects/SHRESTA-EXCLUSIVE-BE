package com.shrestaexclusive.platform.mutation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.multipart.MultipartFile;

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

    public static String multipart(
            ObjectMapper objectMapper,
            String method,
            String path,
            List<MultipartFile> files,
            Map<String, Object> fields
    ) {
        List<Map<String, Object>> fileFingerprints = files.stream()
                .map(file -> {
                    Map<String, Object> fileValue = new LinkedHashMap<>();
                    fileValue.put("name", file.getOriginalFilename());
                    fileValue.put("contentType", file.getContentType());
                    fileValue.put("size", file.getSize());
                    return fileValue;
                })
                .toList();

        Map<String, Object> value = new LinkedHashMap<>();
        value.put("method", method);
        value.put("path", path);
        value.put("files", fileFingerprints);
        value.put("fields", fields);
        return hash(objectMapper, value);
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
