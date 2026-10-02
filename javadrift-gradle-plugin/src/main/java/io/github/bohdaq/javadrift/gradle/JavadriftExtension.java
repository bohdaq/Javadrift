package io.github.bohdaq.javadrift.gradle;
import org.gradle.api.provider.*;
public abstract class JavadriftExtension {
    public abstract Property<String> getSince();
    public abstract Property<Boolean> getWarnOnly();
    public abstract Property<String> getFormat();
}
