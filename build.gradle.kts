import org.gradle.api.publish.PublishingExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.maven.publish.base) apply false
}
val sfcomposeRepository = layout.buildDirectory.dir("sfcompose-repository")
subprojects {
    pluginManager.withPlugin("maven-publish") {
        apply(from = rootProject.file("gradle/publishing.gradle"))
        extensions.configure<PublishingExtension> {
            repositories.maven {
                name = "sfcomposeLocal"
                url = sfcomposeRepository.get().asFile.toURI()
            }
        }
    }
}
