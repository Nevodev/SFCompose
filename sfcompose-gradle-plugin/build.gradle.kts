plugins {
    groovy
    `java-gradle-plugin`
    `maven-publish`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

group = "com.nevoit.sfcompose"
version = libs.versions.sfcompose.get()
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
            id = "com.nevoit.sfcompose"
            implementationClass = "com.nevoit.sfcompose.gradle.SFComposePlugin"
        }
    }
}

tasks.processResources {
    val properties = mapOf("version" to libs.versions.sfcompose.get())
    inputs.properties(properties)
    filesMatching("sfcompose-plugin.properties") { expand(properties) }
}
