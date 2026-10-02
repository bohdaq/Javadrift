package io.github.bohdaq.javadrift.core;
public record Finding(String file, int line, int column, String checkId, Severity severity,
                      String reference, String message, String suggestion) implements Comparable<Finding> {
    public int compareTo(Finding o) {
        int c=file.compareTo(o.file); if(c!=0)return c;
        c=Integer.compare(line,o.line);if(c!=0)return c;
        c=Integer.compare(column,o.column);if(c!=0)return c;
        c=checkId.compareTo(o.checkId);return c!=0?c:reference.compareTo(o.reference);
    }
}
