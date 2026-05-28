plugins {
    alias(libs.plugins.jetbrainsKotlinJvm)
}

dependencies {
    implementation(libs.ksp.api)
    implementation(project(":transmission"))
}
