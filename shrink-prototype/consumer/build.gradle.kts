import com.android.build.api.artifact.SingleArtifact

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.sfcompose)
}

val apiScenarios = mapOf(
    "apiRegular" to listOf("wifi:Regular", "0.circle.ar:Regular", "airtag.radiowaves.forward.fill:Regular"),
    "apiBold" to listOf("wifi:Bold"),
    "apiAnimated" to listOf("wifi:Bold:Animated"),
    "apiFill" to listOf("person.fill:Regular"),
    "apiDynamic" to listOf("wifi:DynamicWeight"),
)

sfcompose {
    catalog.set(":sfcompose-catalog")
    automaticDependency.set(false)
    includeVariants.set(apiScenarios.keys.flatMap { listOf("${it}Debug", "${it}Release") }.toSet())
    if (providers.gradleProperty("sfcomposeTestFixtures").isPresent) {
        retain.addAll(listOf("wifi:Regular", "wifi:Regular:Animated", "0.circle.ar:Regular",
            "airtag.radiowaves.forward.fill:Regular", "airtag.radiowaves.forward.fill:Regular:Animated",
            "person.fill:Regular", "person:Regular:Animated", "person.fill:Regular:Animated",
            "airplay.audio:Regular", "airplay.audio:Regular:Animated", "cellularbars:Regular"))
    }
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
        for (name in listOf("expandedClosure", "masterClosure", "compiledDraw", "liveDraw")) {
            create(name) {
                dimension = "scenario"
                manifestPlaceholders["scenarioActivity"] = ".PackedActivity"
                if (name == "compiledDraw" || name == "liveDraw") {
                    applicationIdSuffix = ".$name"
                    signingConfig = signingConfigs.getByName("debug")
                }
            }
        }
        for (name in apiScenarios.keys) {
            create(name) {
                dimension = "scenario"
                manifestPlaceholders["scenarioActivity"] = ".ApiActivity"
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
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    for (scenario in apiScenarios.keys) add("${scenario}Implementation", project(":sfcompose-catalog"))
}

val packs = project(":shrink-prototype:catalog").layout.buildDirectory.dir("generated/weightPacks")
val apiPacks = project(":sfcompose-catalog").layout.buildDirectory.dir("generated/buildCatalog")
val python = providers.gradleProperty("sfcomposePython").orElse("python")
androidComponents.onVariants { variant ->
    if (variant.productFlavors.single().second in setOf("expandedClosure", "masterClosure", "compiledDraw", "liveDraw")) {
        variant.sources.kotlin?.addStaticSourceDirectory("src/packed/java")
        variant.sources.kotlin?.addStaticSourceDirectory(rootProject.file("sfcompose/renderer").absolutePath)
    }
    if (variant.productFlavors.single().second in apiScenarios) {
        variant.sources.kotlin?.addStaticSourceDirectory("src/api/java")
    }
}
androidComponents.onVariants(androidComponents.selector().withBuildType("release")) { variant ->
    val scenario = variant.productFlavors.single().second
    val apiKeys = apiScenarios[scenario]
    val encoding = when (scenario) {
        "expandedClosure" -> "expanded"
        "masterClosure", "compiledDraw" -> "compact"
        "liveDraw" -> "live"
        else -> if (apiKeys != null) "api" else return@onVariants
    }
    val variantTitle = variant.name.replaceFirstChar(Char::uppercaseChar)
    val apkDir = variant.artifacts.get(SingleArtifact.APK)
    val script = rootProject.file("shrink-prototype/report_weight_pack.py")
    val reportFile = layout.buildDirectory.file("reports/symbols/${variant.name}.json")
    val resources = if (apiKeys != null) apiPacks else packs
    val report = tasks.register<Exec>("report${variantTitle}Symbols") {
        inputs.dir(apkDir)
        inputs.dir(resources)
        inputs.property("apiKeys", apiKeys.orEmpty())
        inputs.files(script, rootProject.file("shrink-prototype/generate_weight_packs.py"))
        if (apiKeys != null) inputs.file(rootProject.file("sfcompose-catalog/generate_symbols.py"))
        if (apiKeys != null) inputs.file(rootProject.file("sfcompose-catalog/report_selected_catalog.py"))
        outputs.file(reportFile)
        commandLine(python.get(), "-B", script.absolutePath, encoding, apkDir.get().asFile.absolutePath,
            resources.get().asFile.absolutePath, reportFile.get().asFile.absolutePath, *apiKeys.orEmpty().toTypedArray())
    }
    tasks.matching { it.name == "assemble$variantTitle" }.configureEach { finalizedBy(report) }
}
