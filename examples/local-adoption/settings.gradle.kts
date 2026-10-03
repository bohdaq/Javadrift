pluginManagement {
    includeBuild(providers.gradleProperty("javadriftSource").getOrElse("../.."))
    repositories { gradlePluginPortal(); mavenCentral() }
}
// Also substitute the analysis engine dependency with the local core project.
includeBuild(providers.gradleProperty("javadriftSource").getOrElse("../.."))
rootProject.name = "local-adoption"
