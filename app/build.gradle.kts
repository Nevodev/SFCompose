import javax.inject.Inject
import org.gradle.api.file.FileSystemOperations

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

abstract class PreparePrivateSymbolAssets @Inject constructor(private val files: FileSystemOperations) : DefaultTask() {
    @get:InputFiles abstract val sourceAssets: ConfigurableFileCollection
    @get:OutputDirectory abstract val output: DirectoryProperty

    @TaskAction
    fun prepare() {
        files.sync {
            from(sourceAssets) { into("private") }
            into(output)
        }
    }
}

// The browser compiles the renderer directly; adding a catalog AAR would duplicate those classes.
val privateSymbolAssets = configurations.create("privateSymbolAssets") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies {
    add(privateSymbolAssets.name, project(mapOf("path" to ":sfcompose-catalog-private", "configuration" to "sfcomposeBrowserAssets")))
}
val preparePrivateSymbolAssets = tasks.register<PreparePrivateSymbolAssets>("preparePrivateSymbolAssets") {
    sourceAssets.from(privateSymbolAssets)
    output.set(layout.buildDirectory.dir("generated/privateSymbolAssets"))
}

android {
    namespace = "com.nevoit.sfcompose"
    compileSdk {
        version = release(libs.versions.androidCompileSdk.get().toInt()){
            minorApiLevel = libs.versions.androidCompileSdkMinor.get().toInt()
        }
    }

    defaultConfig {
        applicationId = "com.nevoit.sfcompose"
        minSdk = libs.versions.androidMinSdk.get().toInt()
        targetSdk = libs.versions.androidTargetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            applicationIdSuffix = ".benchmark"
            isDebuggable = false
            isProfileable = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.jvmArgs("--add-opens=java.base/java.nio=ALL-UNNAMED")
        }
    }
}

androidComponents.onVariants { variant ->
    variant.sources.kotlin?.addStaticSourceDirectory(rootProject.file("sfcompose/renderer").absolutePath)
    variant.sources.assets?.addGeneratedSourceDirectory(preparePrivateSymbolAssets, PreparePrivateSymbolAssets::output)
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
