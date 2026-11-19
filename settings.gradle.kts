pluginManagement {
    includeBuild("sfcompose-gradle-plugin")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "SF Compose"
include(":app")
include(":benchmark")
include(":sfcompose")
include(":sfcompose-catalog")
include(":sfcompose-codegen")
include(":sfcompose-catalog-private")
include(":api-demo")
include(":shrink-prototype:catalog")
include(":shrink-prototype:consumer")
