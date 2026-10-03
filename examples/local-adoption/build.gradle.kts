plugins {
    kotlin("jvm") version "2.2.21"
    id("io.github.bohdaq.javadrift")
}
group = "demo"
version = "1.0.0"
repositories { mavenCentral() }
kotlin { jvmToolchain(17) }
javadrift {
    format.set("json")
    warnOnly.set(providers.gradleProperty("warnOnly").map(String::toBoolean).orElse(false))
}
