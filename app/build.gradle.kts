import javax.inject.Inject
import org.gradle.process.ExecOperations

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

abstract class PrepareBrowserSymbolAssets @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:InputDirectory abstract val publicAssets: DirectoryProperty
    @get:InputFiles abstract val privateAssets: ConfigurableFileCollection
    @get:InputFiles abstract val publicCatalog: ConfigurableFileCollection
    @get:InputFiles abstract val privateCatalog: ConfigurableFileCollection
    @get:InputFile abstract val script: RegularFileProperty
    @get:Input abstract val python: Property<String>
    @get:OutputDirectory abstract val output: DirectoryProperty

    @TaskAction
    fun prepare() {
        exec.exec {
            commandLine(python.get(), "-B", script.get().asFile,
                "--public-assets", publicAssets.get().asFile,
                "--private-assets", privateAssets.singleFile,
                "--public-catalog", publicCatalog.singleFile,
                "--private-catalog", privateCatalog.singleFile,
                "--output", output.get().asFile)
        }
    }
}

// The browser compiles the renderer directly; adding a catalog AAR would duplicate those classes.
val privateSymbolAssets = configurations.create("privateSymbolAssets") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
val publicBuildData = configurations.create("publicBuildData") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
val privateBuildData = configurations.create("privateBuildData") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
dependencies {
    add(privateSymbolAssets.name, project(mapOf("path" to ":sfcompose-catalog-private", "configuration" to "sfcomposeBrowserAssets")))
    add(publicBuildData.name, project(mapOf("path" to ":sfcompose-catalog", "configuration" to "sfcomposeBuildData")))
    add(privateBuildData.name, project(mapOf("path" to ":sfcompose-catalog-private", "configuration" to "sfcomposeBuildData")))
}
val prepareBrowserSymbolAssets = tasks.register<PrepareBrowserSymbolAssets>("prepareBrowserSymbolAssets") {
    publicAssets.set(layout.projectDirectory.dir("src/main/assets"))
    privateAssets.from(privateSymbolAssets)
    publicCatalog.from(publicBuildData)
    privateCatalog.from(privateBuildData)
    script.set(layout.projectDirectory.file("prepare_browser_assets.py"))
    python.set(providers.gradleProperty("sfcomposePython").orElse("python"))
    output.set(layout.buildDirectory.dir("generated/browserSymbolAssets"))
}

android {
    namespace = "com.nevoit.sfcompose"
    sourceSets.getByName("main").assets.directories.clear()
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
    variant.sources.assets?.addGeneratedSourceDirectory(prepareBrowserSymbolAssets, PrepareBrowserSymbolAssets::output)
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
