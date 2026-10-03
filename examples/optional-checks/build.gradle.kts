plugins {
    java
    id("io.github.bohdaq.javadrift")
}
group = "demo"
version = "1.0.0"
repositories { mavenCentral() }
java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }
dependencies { implementation("com.fasterxml.jackson.core:jackson-databind:2.18.3") }
javadrift {
    format.set("json")
    warnOnly.set(providers.gradleProperty("warnOnly").map(String::toBoolean).orElse(false))
}
