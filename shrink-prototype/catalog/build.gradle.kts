import javax.inject.Inject

plugins {
    alias(libs.plugins.android.library)
}

abstract class GenerateWeightPacks @Inject constructor(private val exec: org.gradle.process.ExecOperations) : DefaultTask() {
    @get:InputFile abstract val generator: RegularFileProperty
    @get:InputDirectory abstract val sourceSymbols: DirectoryProperty
    @get:Input abstract val python: Property<String>
    @get:OutputDirectory abstract val kotlinDir: DirectoryProperty
    @get:OutputDirectory abstract val resourceDir: DirectoryProperty
    @get:OutputFile abstract val inventory: RegularFileProperty

    @TaskAction
    fun generate() {
        exec.exec {
            commandLine(python.get(), generator.get().asFile.absolutePath, inventory.get().asFile.parent)
        }
    }
}

val packDir = layout.buildDirectory.dir("generated/weightPacks")
val generateWeightPacks = tasks.register<GenerateWeightPacks>("generateWeightPacks") {
    generator.set(rootProject.layout.projectDirectory.file("shrink-prototype/generate_weight_packs.py"))
    sourceSymbols.set(rootProject.layout.projectDirectory.dir("app/src/main/assets/symbols"))
    python.set(providers.gradleProperty("sfcomposePython").orElse("python"))
    kotlinDir.set(packDir.map { it.dir("java") })
    resourceDir.set(packDir.map { it.dir("res") })
    inventory.set(packDir.map { it.file("inventory.json") })
}

android {
    namespace = "com.nevoit.shrinkprototype.catalog"
    compileSdk {
        version = release(libs.versions.androidCompileSdk.get().toInt())
    }
    defaultConfig {
        minSdk = libs.versions.androidMinSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

androidComponents.onVariants { variant ->
    variant.sources.kotlin?.addGeneratedSourceDirectory(generateWeightPacks, GenerateWeightPacks::kotlinDir)
    variant.sources.res?.addGeneratedSourceDirectory(generateWeightPacks, GenerateWeightPacks::resourceDir)
}
