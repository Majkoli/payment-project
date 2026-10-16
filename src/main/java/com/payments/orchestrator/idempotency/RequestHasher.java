package com.payments.orchestrator.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Computes a stable SHA-256 fingerprint of a request payload. */
public final class RequestHasher {

    /** Canonical JSON: sorted properties and map keys, so field order never changes the hash. */
    private static final ObjectMapper CANONICAL_MAPPER = JsonMapper.builder()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .build();

    private RequestHasher() {
    }

    /** @return 64-character lower-case hex SHA-256 of the canonical JSON form of {@code body}. */
    public static String computeSha256(Object body) {
        try {
            byte[] canonical = CANONICAL_MAPPER.writeValueAsBytes(body);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical);
            return HexFormat.of().formatHex(digest);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Request body cannot be serialized for hashing", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
