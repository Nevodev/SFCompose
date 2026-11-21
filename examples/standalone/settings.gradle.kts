pluginManagement {
    repositories {
        maven {
            url = uri(providers.gradleProperty("sfcomposeRepository").orElse("../../build/sfcompose-repository").get())
            content { includeGroupByRegex("io\\.github\\.nevodev(\\..*)?") }
        }
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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven {
            url = uri(providers.gradleProperty("sfcomposeRepository").orElse("../../build/sfcompose-repository").get())
            content { includeGroup("io.github.nevodev") }
        }
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files(providers.gradleProperty("versionsFile").orElse("../../gradle/libs.versions.toml").get()))
        }
    }
}

rootProject.name = "SFCompose Standalone Consumer"
include(":direct")
if (!providers.gradleProperty("directOnly").isPresent) include(":with-report")
if (providers.gradleProperty("privateDemo").isPresent) {
    include(":private-addon")
    include(":private-unused")
    include(":private-only")
}
