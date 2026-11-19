plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.sfcompose)
}

sfcompose {
    additionalCatalogs.add("com.nevoit.sfcompose:sfcompose-catalog-private:${libs.versions.sfcompose.get()}")
}

android {
    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
