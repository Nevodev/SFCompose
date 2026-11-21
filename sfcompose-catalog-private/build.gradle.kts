import javax.inject.Inject
import org.gradle.process.ExecOperations

plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

abstract class GeneratePrivateBuildCatalog @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:InputFiles abstract val scripts: ConfigurableFileCollection
    @get:InputDirectory abstract val assets: DirectoryProperty
    @get:Input abstract val python: Property<String>
    @get:Input abstract val names: Property<String>
    @get:OutputDirectory abstract val output: DirectoryProperty

    @TaskAction fun generate() {
        exec.exec {
            commandLine(python.get(), "-B", scripts.single { it.name == "generate_build_catalog.py" },
                output.get().asFile, "--names", names.get(), "--assets", assets.get().asFile,
                "--symbol-root", "PrivateSFSymbols")
        }
    }
}

val generated = layout.buildDirectory.dir("generated/buildCatalog")
val generateBuildCatalog = tasks.register<GeneratePrivateBuildCatalog>("generateBuildCatalog") {
    scripts.from(rootProject.file("sfcompose-catalog/generate_build_catalog.py"),
        rootProject.file("sfcompose-catalog/generate_symbols.py"),
        rootProject.file("shrink-prototype/generate_weight_packs.py"))
    val assetPath = providers.gradleProperty("sfcomposePrivateAssets")
        .orElse(rootProject.layout.buildDirectory.dir("private-assets").map { it.asFile.absolutePath })
    val rootDirectory = rootProject.layout.projectDirectory
    assets.set(assetPath.map { rootDirectory.dir(it) })
    python.set(providers.gradleProperty("sfcomposePython").orElse("python"))
    names.set(providers.gradleProperty("sfcomposePrivateCatalog").orElse(""))
    output.set(generated)
}

val codegen = configurations.create("codegen") { isCanBeConsumed = false }
dependencies { add("codegen", project(":sfcompose-codegen")) }
val catalogJar = layout.buildDirectory.file("generated/thinCatalog/symbols.jar")
val catalogSources = layout.buildDirectory.dir("generated/thinCatalog/sources")
val generateCatalogJar = tasks.register<JavaExec>("generateCatalogJar") {
    dependsOn(generateBuildCatalog)
    classpath = codegen
    mainClass.set("com.nevoit.sfcompose.codegen.ThinCatalogGenerator")
    inputs.file(generated.map { it.file("namespaces.tsv") })
    outputs.file(catalogJar)
    outputs.dir(catalogSources)
    args(generated.get().file("namespaces.tsv").asFile.absolutePath, catalogJar.get().asFile.absolutePath,
        catalogSources.get().asFile.absolutePath)
}
val catalogSourcesJar = tasks.register<Jar>("catalogSourcesJar") {
    dependsOn(generateCatalogJar)
    archiveClassifier.set("sources")
    from(catalogSources)
}

val buildData = configurations.create("sfcomposeBuildData") {
    isCanBeConsumed = true
    isCanBeResolved = false
}
artifacts.add(buildData.name, generated.map { it.file("build-data.zip") }) { builtBy(generateBuildCatalog) }

android {
    namespace = "com.nevoit.sfcompose.catalog.privateglyphs"
    compileSdk {
        version = release(libs.versions.androidCompileSdk.get().toInt()) {
            minorApiLevel = libs.versions.androidCompileSdkMinor.get().toInt()
        }
    }
    defaultConfig { minSdk = libs.versions.androidMinSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    publishing { singleVariant("release") }
}

afterEvaluate {
    publishing.publications.register<MavenPublication>("release") {
        from(components["release"])
        artifact(catalogSourcesJar)
        artifact(generated.map { it.file("build-data.zip") }) {
            classifier = "build-data"
            extension = "zip"
            builtBy(generateBuildCatalog)
        }
    }
}

dependencies {
    api(project(":sfcompose"))
    api(files(catalogJar).builtBy(generateCatalogJar))
}
