plugins {
    groovy
    `java-gradle-plugin`
    `maven-publish`
    alias(libs.plugins.maven.publish.base)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

apply(from = "../gradle/publishing.gradle")
publishing {
    repositories.maven {
        name = "sfcomposeLocal"
        url = file("../build/sfcompose-repository").toURI()
    }
}

dependencies {
    testImplementation(libs.junit)
    implementation(libs.selection.asm)
    implementation(libs.selection.asm.tree)
    implementation(libs.selection.asm.analysis)
    compileOnly(localGroovy())
    compileOnly("com.android.tools.build:gradle-api:${libs.versions.agp.get()}")
}

gradlePlugin {
    plugins {
        create("sfcompose") {
            id = libs.plugins.sfcompose.get().pluginId
            displayName = "SFCompose"
            description = "Selects SFCompose symbol assets from catalog references during Android builds"
            implementationClass = "com.nevoit.sfcompose.gradle.SFComposePlugin"
        }
    }
}

tasks.processResources {
    val properties = mapOf("version" to project.version.toString(), "group" to project.group.toString())
    inputs.properties(properties)
    filesMatching("sfcompose-plugin.properties") { expand(properties) }
}
