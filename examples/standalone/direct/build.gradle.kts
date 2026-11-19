plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.sfcompose)
}

sfcompose { automaticDependency.set(false) }

dependencies {
    implementation("com.nevoit.sfcompose:sfcompose-catalog:${libs.versions.sfcompose.get()}")
}
