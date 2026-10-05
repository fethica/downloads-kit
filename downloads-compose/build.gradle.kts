plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    id("maven-publish")
}

android {
    namespace = "com.fethica.downloadskit.compose"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }
    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures { compose = true }
    // Every string this module ships already starts with `downloads_`; the prefix
    // makes that a build rule instead of a convention.
    resourcePrefix = "downloads_"
    publishing { singleVariant("release") { withSourcesJar() } }
    // No baseline: this module is clean, so lintDebug is a plain gate on it and
    // any error a change introduces fails the build straight away.
    kotlin {
        // The module is consumed as a library: every public declaration states its
        // visibility and its return type, so a slip cannot widen the surface by accident.
        explicitApi()
    }
}

dependencies {
    // The screens take and hand back the headless module's types.
    api(project(":downloads"))
    // `api`, not `implementation`: the surface hands the app Compose types (Modifier,
    // Color, PaddingValues) that a caller must be able to name, and must resolve at the
    // same versions, hence the BOM is `api` too.
    api(platform(libs.androidx.compose.bom))
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.ui.graphics)
    api(libs.androidx.compose.foundation)
    api(libs.androidx.compose.material3)
    testImplementation(libs.junit)
}

group = property("GROUP") as String
// On JitPack (JITPACK=true) the version must follow the tag being built (VERSION), so an rc tag does not publish a POM versioned with the release VERSION_NAME.
version = if (System.getenv("JITPACK") == "true" && !System.getenv("VERSION").isNullOrBlank()) {
    System.getenv("VERSION")
} else {
    property("VERSION_NAME") as String
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = project.group.toString()
            artifactId = "downloads-compose"
            version = project.version.toString()
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("downloads-compose")
                description.set("Jetpack Compose offline screen and download button for the downloads module")
                licenses { license { name.set("MIT") } }
            }
        }
    }
}
