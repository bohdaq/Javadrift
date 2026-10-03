pluginManagement {
    includeBuild(providers.gradleProperty("javadriftSource").getOrElse("../.."))
    repositories { gradlePluginPortal(); mavenCentral() }
}
rootProject.name = "local-adoption"
