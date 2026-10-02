plugins { base }
allprojects {
    group = "io.github.bohdaq"
    version = "0.1.0-SNAPSHOT"
    repositories { mavenCentral() }
}
subprojects {
    plugins.withType<JavaPlugin> {
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
        tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
        tasks.withType<Test>().configureEach { useJUnitPlatform() }
    }
}
tasks.check { dependsOn(":javadrift-core:check", ":javadrift-gradle-plugin:check") }
