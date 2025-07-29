// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.jetbrains.kotlin.android) apply false
    alias(libs.plugins.jetbrainsKotlinJvm) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.dokka)
}

// Version management for API docs
val apiVersion = "0.x"

// Configure multi-module Dokka documentation
tasks.dokkaHtmlMultiModule.configure {
    moduleName.set("Transmission")
    outputDirectory.set(layout.buildDirectory.dir("site/api/$apiVersion"))

    // Include all library modules but exclude samples
    includes.from("docs/api-overview.md")

    dependsOn(":transmission:dokkaHtml")
    dependsOn(":transmission-test:dokkaHtml")
    dependsOn(":transmission-viewmodel:dokkaHtml")
}

// Convenience task for generating API documentation
tasks.register("generateApiDocs") {
    group = "documentation"
    description = "Generates API documentation for all Transmission modules"
    dependsOn("dokkaHtmlMultiModule")

    doLast {
        println("📚 API Documentation generated successfully!")
        println("📖 Location: ${layout.buildDirectory.get().asFile}/site/api/$apiVersion/index.html")
    }
}

// Build MkDocs site
tasks.register("buildMkDocs") {
    group = "documentation"
    description = "Builds MkDocs documentation site"

    doLast {
        val result = exec {
            commandLine("mkdocs", "build")
            isIgnoreExitValue = true
        }
        if (result.exitValue != 0) {
            throw GradleException("MkDocs build failed. Make sure mkdocs is installed: pip install mkdocs-material pymdown-extensions")
        }
        println("📖 MkDocs site built successfully!")
    }
}

// Combined documentation task for MkDocs + API
tasks.register("generateDocs") {
    group = "documentation"
    description = "Generates complete documentation site with MkDocs + API docs"
    dependsOn("buildMkDocs")
    dependsOn("generateApiDocs")

    // Ensure API docs are generated after MkDocs build
    tasks.findByName("generateApiDocs")?.mustRunAfter("buildMkDocs")

    doLast {
        println("🚀 Complete documentation site generated successfully!")
        println("📖 Site: ${layout.buildDirectory.get().asFile}/site/")
        println("📚 API: ${layout.buildDirectory.get().asFile}/site/api/$apiVersion/")
        println("🌐 To serve locally: ./gradlew serveDocs")
    }
}

// Local development task
tasks.register("serveDocs") {
    group = "documentation"
    description = "Builds and serves documentation locally for development"
    dependsOn("generateDocs")

    doLast {
        val siteDir = layout.buildDirectory.get().asFile.resolve("site")
        println("🚀 Starting local documentation server...")
        println("📖 Main site: http://localhost:8000")
        println("📚 API docs: http://localhost:8000/api/$apiVersion/")
        println("💡 The API docs link is available on the main page!")
        println("🛑 Press Ctrl+C to stop the server")
        println("")

        // Use Python's built-in HTTP server to serve the complete site
        exec {
            workingDir = siteDir
            commandLine("python3", "-m", "http.server", "8000")
        }
    }
}
