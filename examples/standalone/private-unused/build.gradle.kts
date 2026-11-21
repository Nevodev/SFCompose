plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.sfcompose)
}

sfcompose {
    additionalCatalogs.add("io.github.nevodev:sfcompose-catalog-private:${libs.versions.sfcompose.get()}")
}
