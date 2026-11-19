plugins { java }

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    implementation(libs.catalog.kotlin.metadata)
    implementation(libs.catalog.asm)
}
