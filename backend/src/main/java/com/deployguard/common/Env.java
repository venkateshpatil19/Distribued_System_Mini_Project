package com.deployguard.common;

// Reads configuration from environment variables with typed defaults.
public final class Env {
    private Env() {}

    public static String str(String name, String def) {
        String v = System.getenv(name);
        return (v == null || v.isBlank()) ? def : v.trim();
    }

    public static String required(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("Missing required environment variable " + name);
        }
        return v.trim();
    }

    public static int integer(String name, int def) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) return def;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(name + " must be an integer but was '" + v + "'");
        }
    }

    public static long lng(String name, long def) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) return def;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(name + " must be a number but was '" + v + "'");
        }
    }

    public static double dbl(String name, double def) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) return def;
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException(name + " must be a decimal number but was '" + v + "'");
        }
    }
}
