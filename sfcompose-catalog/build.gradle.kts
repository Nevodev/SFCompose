import javax.inject.Inject
import org.gradle.process.ExecOperations

plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

abstract class GenerateBuildCatalog @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:InputFiles abstract val scripts: ConfigurableFileCollection
    @get:InputDirectory abstract val sourceSymbols: DirectoryProperty
    @get:Input abstract val python: Property<String>
    @get:Input abstract val names: Property<String>
    @get:Optional @get:InputFile abstract val bootstrapInventory: RegularFileProperty
    @get:OutputDirectory abstract val output: DirectoryProperty

    @TaskAction fun generate() {
        val arguments = mutableListOf<Any>(python.get(), "-B",
            scripts.single { it.name == "generate_build_catalog.py" }, output.get().asFile, "--names", names.get())
        bootstrapInventory.orNull?.let { arguments.addAll(listOf("--inventory", it.asFile)) }
        exec.exec { commandLine(arguments) }
    }
}

val generated = layout.buildDirectory.dir("generated/buildCatalog")
val generateBuildCatalog = tasks.register<GenerateBuildCatalog>("generateBuildCatalog") {
    scripts.from(file("generate_build_catalog.py"), file("generate_symbols.py"),
        rootProject.file("shrink-prototype/generate_weight_packs.py"))
    sourceSymbols.set(rootProject.layout.projectDirectory.dir("app/src/main/assets/symbols"))
    python.set(providers.gradleProperty("sfcomposePython").orElse("python"))
    names.set(providers.gradleProperty("sfcomposeCatalog").orElse(""))
    providers.gradleProperty("sfcomposeBootstrapInventory").orNull?.let {
        bootstrapInventory.set(rootProject.layout.projectDirectory.file(it))
    }
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
    namespace = "com.nevoit.sfcompose.catalog"
    compileSdk { version = release(libs.versions.androidCompileSdk.get().toInt()) }
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
