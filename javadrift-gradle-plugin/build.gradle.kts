plugins { `java-gradle-plugin` }
dependencies {
    implementation(project(":javadrift-core"))
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
gradlePlugin {
    plugins {
        create("javadrift") {
            id = "io.github.bohdaq.javadrift"
            implementationClass = "io.github.bohdaq.javadrift.gradle.JavadriftPlugin"
            displayName = "Javadrift"
            description = "Offline stale documentation detection for JVM projects"
        }
    }
}
