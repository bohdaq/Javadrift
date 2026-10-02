package io.github.bohdaq.javadrift.core;
public enum Check {
    JD001("unknown-type", Severity.ERROR, "A qualified project type no longer exists."),
    JD002("unknown-member", Severity.ERROR, "A uniquely resolved project type has no documented member."),
    JD003("signature-mismatch", Severity.WARNING, "No public overload matches the documented arguments."),
    JD004("removed-symbol", Severity.ERROR, "A symbol removed since the base Git ref is still documented."),
    JD005("deprecated-reference", Severity.WARNING, "A documented API is deprecated for removal."),
    JD006("stale-version", Severity.ERROR, "Project dependency coordinates show an outdated release version."),
    JD007("broken-path", Severity.ERROR, "A repository path or relative document link is missing."),
    JD008("snippet-compile", Severity.OFF, "A Java example fails to compile. Supply the classpath or suppress partial examples."),
    JD009("unknown-property", Severity.OFF, "A documented configuration key has no declaration in project resources.");
    public final String key; public final Severity defaultSeverity; public final String explanation;
    Check(String key, Severity level, String explanation) {this.key=key;this.defaultSeverity=level;this.explanation=explanation;}
    public static Check from(String value) {
        for (Check c : values()) if (c.name().equals(value) || c.key.equals(value)) return c;
        throw new IllegalArgumentException("Unknown check: " + value);
    }
}
