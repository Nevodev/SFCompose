import org.gradle.api.publish.PublishingExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.test) apply false
}
val sfcomposeVersion = libs.versions.sfcompose.get()
val sfcomposeRepository = layout.buildDirectory.dir("sfcompose-repository")
subprojects {
    pluginManager.withPlugin("maven-publish") {
        group = "com.nevoit.sfcompose"
        version = sfcomposeVersion
        extensions.configure<PublishingExtension> {
            repositories.maven {
                name = "sfcomposeLocal"
                url = sfcomposeRepository.get().asFile.toURI()
            }
        }
    }
}
