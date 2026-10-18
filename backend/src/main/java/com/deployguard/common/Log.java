package com.deployguard.common;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

// Structured JSON-lines logger so `kubectl logs` output can be filtered with jq.
public final class Log {
    private static volatile String node = "unknown";

    private Log() {}

    public static void setNode(String nodeId) {
        node = nodeId;
    }

    public static void info(String event, Object... kv) {
        emit("INFO", event, null, kv);
    }

    public static void warn(String event, Object... kv) {
        emit("WARN", event, null, kv);
    }

    public static void error(String event, Throwable t, Object... kv) {
        emit("ERROR", event, t, kv);
    }

    private static void emit(String level, String event, Throwable t, Object[] kv) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("ts", Instant.now().toString());
        line.put("level", level);
        line.put("node", node);
        line.put("event", event);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            line.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        if (t != null) {
            line.put("error", t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        String out;
        try {
            out = Json.write(line);
        } catch (RuntimeException e) {
            out = "{\"level\":\"" + level + "\",\"event\":\"" + event + "\"}";
        }
        if ("ERROR".equals(level)) System.err.println(out);
        else System.out.println(out);
    }
}
