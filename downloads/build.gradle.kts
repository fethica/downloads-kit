plugins {
    alias(libs.plugins.android.library)
    id("maven-publish")
}

android {
    namespace = "com.fethica.downloadskit"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }
    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    publishing { singleVariant("release") { withSourcesJar() } }
    testOptions {
        // Robolectric loads the merged resources, which declare the download service.
        unitTests.isIncludeAndroidResources = true
    }
    // No baseline: this module is clean, so lintDebug is a plain gate on it and
    // any error a change introduces fails the build straight away.
    kotlin {
        // The module is consumed as a library: every public declaration states its
        // visibility and its return type, so a slip cannot widen the surface by accident.
        explicitApi()
    }
}

dependencies {
    // `api`, not `implementation`: the surface hands the app media3 types
    // (DataSource.Factory for cacheDataSourceFactory, Cache, HttpDataSource.Factory)
    // and coroutines types (Flow, MutableStateFlow) that a caller must be able to name.
    api(libs.androidx.media3.common)
    api(libs.androidx.media3.datasource)
    api(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.database)
    // Declared here rather than left to Compose, so the headless module stands on its own.
    api(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.annotation)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
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
            artifactId = "downloads"
            version = project.version.toString()
            afterEvaluate { from(components["release"]) }
            pom {
                name.set("downloads")
                description.set("Headless Media3 downloads for Android: a foreground download service, a state flow per item, grouping and a cache-backed data source")
                licenses { license { name.set("MIT") } }
            }
        }
    }
}
