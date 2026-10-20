plugins {
    id("com.android.application") version "8.13.2"
    id("org.jetbrains.kotlin.android") version "2.2.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.0"
}

listOf("debug", "release").forEach {
    check(file("libs/weartak-plugin-sdk-$it.aar").exists()) {
        "WearTAK plugin SDK missing: app/libs/weartak-plugin-sdk-$it.aar. Run scripts/fetch-sdk (see README)."
    }
}

android {
    namespace = "com.weartak.plugin.sextant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.weartak.plugin.sextant"
        minSdk = 30
        targetSdk = 34
        // Same scheme as WearTAK (WEARTAK-239): CI builds get a unique, increasing versionCode
        // per GitLab pipeline so MDMs such as Knox Manage accept each upload, and a versionName
        // that identifies the pipeline and commit. Bump the base for each official vX.Y.Z[.B] tag.
        val baseVersionCode = 580
        val baseVersionName = "5.8.0"
        val ciPipelineIid = System.getenv("CI_PIPELINE_IID")?.toIntOrNull()
        if (ciPipelineIid != null) {
            if (ciPipelineIid !in 0..9999) {
                throw GradleException("CI_PIPELINE_IID $ciPipelineIid exceeds the versionCode pipeline range (0-9999).")
            }
            val sha = System.getenv("CI_COMMIT_SHORT_SHA") ?: "local"
            versionCode = baseVersionCode * 10000 + ciPipelineIid
            versionName = "$baseVersionName-p$ciPipelineIid+$sha"
        } else {
            versionCode = baseVersionCode
            versionName = baseVersionName
        }
        base.archivesName.set("Sextant-$baseVersionName-$versionCode")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

apply(from = rootProject.file("gradle/weartak-sideload-debug.gradle"))
apply(from = rootProject.file("gradle/weartak-sideload-production.gradle"))

dependencies {
    debugImplementation(files("libs/weartak-plugin-sdk-debug.aar"))
    releaseImplementation(files("libs/weartak-plugin-sdk-release.aar"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.wear.compose:compose-material:1.4.0")
    implementation("androidx.wear.compose:compose-foundation:1.4.0")
    implementation("androidx.wear:wear-input:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    implementation("org.maplibre.compose:maplibre-compose-material3:0.12.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
}
