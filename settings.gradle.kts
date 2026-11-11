pluginManagement {
    repositories {
        google()
        // Google-hosted mirror of Maven Central first: Central rate-limits shared CI egress IPs (HTTP 429).
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}
rootProject.name = "EndlessDrive"
include(":core", ":engine", ":app", ":desktop")
