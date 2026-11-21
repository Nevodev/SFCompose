plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    `maven-publish`
}

android {
    namespace = "com.nevoit.sfcompose.library"
    compileSdk {
        version = release(libs.versions.androidCompileSdk.get().toInt()) {
            minorApiLevel = libs.versions.androidCompileSdkMinor.get().toInt()
        }
    }
    defaultConfig { minSdk = libs.versions.androidMinSdk.get().toInt() }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    publishing { singleVariant("release") { withSourcesJar() } }
}

androidComponents.onVariants { variant ->
    variant.sources.kotlin?.addStaticSourceDirectory(file("renderer").absolutePath)
}

afterEvaluate {
    publishing.publications.register<MavenPublication>("release") {
        from(components["release"])
    }
}

dependencies {
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    api(libs.androidx.core.ktx)
}
