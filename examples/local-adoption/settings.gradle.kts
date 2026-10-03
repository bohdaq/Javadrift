pluginManagement {
    includeBuild(providers.gradleProperty("javadriftSource").getOrElse("../.."))
    plugins { id("org.jetbrains.kotlin.jvm") version providers.gradleProperty("kotlinVersion").getOrElse("2.2.21") }
    repositories { gradlePluginPortal(); mavenCentral() }
}
// Also substitute the analysis engine dependency with the local core project.
includeBuild(providers.gradleProperty("javadriftSource").getOrElse("../.."))
rootProject.name = "local-adoption"
