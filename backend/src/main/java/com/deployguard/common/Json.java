package com.deployguard.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

// One shared Jackson mapper for HTTP bodies, gRPC payloads and the state files.
public final class Json {
    public static final ObjectMapper M = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private Json() {}

    public static String write(Object o) {
        try {
            return M.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] bytes(Object o) {
        try {
            return M.writeValueAsBytes(o);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static <T> T read(String s, Class<T> type) {
        try {
            return M.readValue(s, type);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid JSON: " + e.getMessage());
        }
    }

    public static <T> T read(byte[] b, Class<T> type) {
        try {
            return M.readValue(b, type);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid JSON: " + e.getMessage());
        }
    }

    public static <T> T read(String s, TypeReference<T> type) {
        try {
            return M.readValue(s, type);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid JSON: " + e.getMessage());
        }
    }

    public static JsonNode tree(String s) {
        try {
            return M.readTree(s);
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid JSON: " + e.getMessage());
        }
    }

    public static <T> T convert(Object from, Class<T> type) {
        return M.convertValue(from, type);
    }

    public static Map<String, Object> map(Object from) {
        return M.convertValue(from, new TypeReference<Map<String, Object>>() {});
    }

    public static ObjectNode obj() {
        return M.createObjectNode();
    }

    // Marks a value that must be bound as JSONB in SQL.
    public record Raw(String json) {}

    public static Raw raw(Object o) {
        return new Raw(write(o));
    }
}
