package io.github.bohdaq.javadrift.core;
public enum Severity {
    OFF, WARNING, ERROR;
    public static Severity parse(String value) {
        if (value == null) throw new IllegalArgumentException("Severity must be error, warning or off");
        return switch (value.toLowerCase(java.util.Locale.ROOT)) {
            case "off" -> OFF; case "warning", "warn" -> WARNING; case "error" -> ERROR;
            default -> throw new IllegalArgumentException("Unknown severity: " + value);
        };
    }
}
