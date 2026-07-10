plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "dev.hermes.hermex"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.hermes.hermex"
        minSdk = 24
        targetSdk = 35
        // versionCode: use the CI run number if available (GITHUB_RUN_NUMBER),
        // otherwise fall back to 1 for local builds. This ensures every CI
        // build has a higher versionCode than the previous one, so Android
        // treats it as an update instead of refusing with "package conflicts
        // with an existing package".
        versionCode = (providers.environmentVariable("GITHUB_RUN_NUMBER").orNull?.toIntOrNull() ?: 1)
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        create("release") {
            // Treat both unset AND empty-string env vars as "not provided" —
            // GitHub Actions substitutes "" when a secret is missing, which
            // would otherwise crash `file(keystoreFile)` below.
            val keystoreFile = providers.environmentVariable("HERMES_KEYSTORE_FILE").orNull?.takeIf { it.isNotBlank() }
            val keystorePassword = providers.environmentVariable("HERMES_KEYSTORE_PASSWORD").orNull?.takeIf { it.isNotBlank() }
            val keyAlias = providers.environmentVariable("HERMES_KEY_ALIAS").orNull?.takeIf { it.isNotBlank() }
            val keyPassword = providers.environmentVariable("HERMES_KEY_PASSWORD").orNull?.takeIf { it.isNotBlank() }
            if (keystoreFile != null && keystorePassword != null && keyAlias != null && keyPassword != null) {
                this.storeFile = file(keystoreFile)
                this.storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Signing config is wired from env vars (HERMES_KEYSTORE_FILE,
            // HERMES_KEYSTORE_PASSWORD, HERMES_KEY_ALIAS, HERMES_KEY_PASSWORD)
            // when present — typically by CI. Absent locally, the release
            // build falls back to the debug signing config so the artifact
            // is still installable on emulators.
            val releaseSigning = signingConfigs.findByName("release")
            signingConfig = if (releaseSigning?.storeFile != null) releaseSigning!! else signingConfigs.getByName("debug")
        }
    }

    lint {
        // Project is early scaffold — don't fail CI on lint warnings yet.
        // Promote to true once Task 6+ UI is in place.
        abortOnError = false
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}

dependencies {
    implementation(project(":core"))

    // Ktor — needed because ChatStream's public API references HttpClient
    // types, and :core uses `implementation` (not `api`) for Ktor.
    implementation("io.ktor:ktor-client-core:3.0.3")

    // Compose / Material
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.navigation:navigation-compose:2.8.0")

    // Image/media
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Markdown rendering (assistant messages with code blocks, lists, etc.)
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.28.0")

    // JSON parsing (for normalizing raw JSON in assistant messages)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.06.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
