import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

subprojects {
    val usesSharedSource = name !in setOf("private-addon", "private-only")
    pluginManager.withPlugin("com.android.application") {
        extensions.configure<ApplicationExtension> {
            namespace = "com.nevoit.sfcompose.example"
            compileSdk {
                version = release(libs.versions.androidCompileSdk.get().toInt()) {
                    minorApiLevel = libs.versions.androidCompileSdkMinor.get().toInt()
                }
            }
            defaultConfig {
                applicationId = "com.nevoit.sfcompose.example"
                minSdk = libs.versions.androidMinSdk.get().toInt()
                targetSdk = libs.versions.androidTargetSdk.get().toInt()
            }
            buildFeatures { compose = true }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_11
                targetCompatibility = JavaVersion.VERSION_11
            }
            buildTypes.named("release") { optimization { enable = true } }
            sourceSets.named("main") {
                manifest.srcFile(rootProject.file("src/main/AndroidManifest.xml"))
            }
        }
        if (usesSharedSource) {
            val sourceDir = rootProject.file("src/main/java").absolutePath
            extensions.getByType<ApplicationAndroidComponentsExtension>().onVariants { variant ->
                variant.sources.kotlin?.addStaticSourceDirectory(sourceDir)
            }
        }
        dependencies.add("implementation", libs.androidx.activity.compose)
        dependencies.add("implementation", libs.androidx.compose.foundation)
    }
}
