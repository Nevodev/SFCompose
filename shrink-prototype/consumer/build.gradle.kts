import com.android.build.api.artifact.SingleArtifact

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.nevoit.shrinkprototype"
    compileSdk {
        version = release(libs.versions.androidCompileSdk.get().toInt())
    }
    defaultConfig {
        applicationId = "com.nevoit.sfcompose.shrinkprototype"
        minSdk = libs.versions.androidMinSdk.get().toInt()
        targetSdk = libs.versions.androidTargetSdk.get().toInt()
        versionCode = 1
        versionName = "1"
        manifestPlaceholders["scenarioActivity"] = ".MainActivity"
    }
    flavorDimensions += "scenario"
    productFlavors {
        create("ltr") { dimension = "scenario" }
        create("rtl") { dimension = "scenario" }
        create("both") { dimension = "scenario" }
        create("single") { dimension = "scenario" }
        create("full") { dimension = "scenario" }
        create("staticWeight") { dimension = "scenario" }
        for (name in listOf("expandedClosure", "masterClosure")) {
            create(name) {
                dimension = "scenario"
                manifestPlaceholders["scenarioActivity"] = ".PackedActivity"
            }
        }
    }
    buildTypes {
        release {
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures { compose = true }
    testOptions.unitTests {
        isIncludeAndroidResources = true
        all { it.jvmArgs("--add-opens=java.base/java.nio=ALL-UNNAMED") }
    }
}

dependencies {
    implementation(project(":shrink-prototype:catalog"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}

val packs = project(":shrink-prototype:catalog").layout.buildDirectory.dir("generated/weightPacks")
val python = providers.gradleProperty("sfcomposePython").orElse("python")
androidComponents.onVariants { variant ->
    if (variant.productFlavors.single().second in setOf("expandedClosure", "masterClosure")) {
        variant.sources.kotlin?.addStaticSourceDirectory("src/packed/java")
        variant.sources.kotlin?.addStaticSourceDirectory(rootProject.file("app/src/main/java/com/nevoit/sfcompose/symbols").absolutePath)
    }
}
androidComponents.onVariants(androidComponents.selector().withBuildType("release")) { variant ->
    val scenario = variant.productFlavors.single().second
    val encoding = when (scenario) {
        "expandedClosure" -> "expanded"
        "masterClosure" -> "compact"
        else -> return@onVariants
    }
    val variantTitle = variant.name.replaceFirstChar(Char::uppercaseChar)
    val apkDir = variant.artifacts.get(SingleArtifact.APK)
    val script = rootProject.file("shrink-prototype/report_weight_pack.py")
    val reportFile = layout.buildDirectory.file("reports/symbols/${variant.name}.json")
    val report = tasks.register<Exec>("report${variantTitle}Symbols") {
        inputs.dir(apkDir)
        inputs.dir(packs)
        inputs.files(script, rootProject.file("shrink-prototype/generate_weight_packs.py"))
        outputs.file(reportFile)
        commandLine(python.get(), "-B", script.absolutePath, encoding, apkDir.get().asFile.absolutePath,
            packs.get().asFile.absolutePath, reportFile.get().asFile.absolutePath)
    }
    tasks.matching { it.name == "assemble$variantTitle" }.configureEach { finalizedBy(report) }
}
