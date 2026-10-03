plugins { id("com.gradle.plugin-publish") version "2.2.1" }
dependencies {
    implementation(project(":javadrift-core"))
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
gradlePlugin {
    website.set("https://github.com/bohdaq/Javadrift")
    vcsUrl.set("https://github.com/bohdaq/Javadrift.git")
    plugins {
        create("javadrift") {
            id = "io.github.bohdaq.javadrift"
            implementationClass = "io.github.bohdaq.javadrift.gradle.JavadriftPlugin"
            tags.set(listOf("documentation", "java", "kotlin", "verification"))
            displayName = "Javadrift"
            description = "Offline stale documentation detection for JVM projects"
        }
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("Javadrift Gradle plugin")
            description.set("Offline stale documentation detection for JVM projects")
            url.set("https://github.com/bohdaq/Javadrift")
            licenses { license { name.set("Apache License, Version 2.0"); url.set("https://www.apache.org/licenses/LICENSE-2.0.txt") } }
            developers { developer { id.set("bohdaq"); name.set("Bohdan Tsap") } }
            scm { url.set("https://github.com/bohdaq/Javadrift"); connection.set("scm:git:https://github.com/bohdaq/Javadrift.git") }
        }
    }
}
