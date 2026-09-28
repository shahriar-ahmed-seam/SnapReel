import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// ─── Release signing ─────────────────────────────────────────────────────────────────────
// Every release must be signed with the one dedicated release key, so installed apps can
// update in place. The key and its passwords never live in the repo: each value comes from an
// SNAPREEL_* environment variable, or else from the untracked keystore.properties at the repo
// root (see README › Releasing). There is deliberately no fallback to the debug key.
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}

fun signingValue(key: String, env: String): String? =
    (System.getenv(env) ?: keystoreProps.getProperty(key))?.takeIf { it.isNotBlank() }

val releaseStorePath = signingValue("storeFile", "SNAPREEL_KEYSTORE_FILE")
val releaseStoreFile = releaseStorePath?.let {
    if (it.startsWith("~/")) File(System.getProperty("user.home"), it.removePrefix("~/")) else rootProject.file(it)
}
val releaseStorePassword = signingValue("storePassword", "SNAPREEL_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "SNAPREEL_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "SNAPREEL_KEY_PASSWORD")

/** What's missing for a release build (checked when a release build runs, not at IDE sync). */
val missingReleaseSigning: List<String> = buildList {
    when {
        releaseStoreFile == null -> add("storeFile / SNAPREEL_KEYSTORE_FILE")
        !releaseStoreFile.isFile -> add("storeFile (not found: $releaseStorePath)")
    }
    if (releaseStorePassword == null) add("storePassword / SNAPREEL_KEYSTORE_PASSWORD")
    if (releaseKeyAlias == null) add("keyAlias / SNAPREEL_KEY_ALIAS")
    if (releaseKeyPassword == null) add("keyPassword / SNAPREEL_KEY_PASSWORD")
}

android {
    namespace = "com.snapreel.app"
    compileSdk = 36

    signingConfigs {
        create("release") {
            releaseStoreFile?.let { storeFile = it }
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    }

    defaultConfig {
        applicationId = "com.snapreel.app"
        minSdk = 26
        targetSdk = 36
        // 1.3.0 is the first release signed with the dedicated release key.
        versionCode = 14
        versionName = "1.3.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Never "debug": a release signed with a machine's debug key can't update installs
            // signed with another key (the cause of the broken v1.2.6 update).
            signingConfig = signingConfigs.getByName("release")
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
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            // Robolectric downloads its android-all jars from the test JVM. Forward the
            // trust store / proxy settings the Gradle JVM was started with (e.g. via
            // GRADLE_OPTS), since forked test JVMs don't inherit them.
            listOf(
                "javax.net.ssl.trustStore",
                "javax.net.ssl.trustStorePassword",
                "javax.net.ssl.trustStoreType",
                "https.proxyHost",
                "https.proxyPort",
                "http.proxyHost",
                "http.proxyPort",
                "http.nonProxyHosts",
            ).forEach { key ->
                System.getProperty(key)?.let { test.systemProperty(key, it) }
            }
        }
    }
}

// Fail fast (before any release work) when the release key isn't configured. Debug builds, unit
// tests and IDE sync are unaffected.
val validateReleaseSigning by tasks.registering {
    group = "verification"
    description = "Fails when the dedicated release signing key is not configured."
    val missing = missingReleaseSigning
    doLast {
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Release signing is not configured (missing: ${missing.joinToString()}). " +
                    "Add keystore.properties or SNAPREEL_* env vars; see README › Releasing. " +
                    "Release builds are never signed with the debug key."
            )
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(validateReleaseSigning) }

dependencies {
    // Compose BOM
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.animation)
    debugImplementation(libs.androidx.ui.tooling)

    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Media3 ExoPlayer
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.session)

    // Coil
    implementation(libs.coil.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Splash
    implementation(libs.androidx.core.splashscreen)

    // Unit tests (JVM + Robolectric)
    testImplementation(composeBom)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotest.property)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.androidx.test.ext.junit.ktx)
    testImplementation(libs.androidx.media3.test.utils)
    testImplementation(libs.androidx.media3.test.utils.robolectric)
    testImplementation(libs.androidx.navigation.testing)
    testImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.test.manifest)
}
