import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// A Firebase opcionális: ha az app/ mappában van google-services.json, bekapcsol.
// Nélküle is lefordul és működik, csak a Fiók fül lesz letiltva.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

// ---------------------------------------------------------------- aláírás
// A saját feltöltési/kiadási kulcs két helyről jöhet:
//  1) GitHub Actions: KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD környezeti változók
//  2) Helyi gép: keystore.properties a projekt gyökerében (storeFile, storePassword, keyAlias, keyPassword)
// Ha egyik sincs, a release build a debug kulccsal készül (telepíthető, de Play-re NEM feltölthető).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(env: String, prop: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: keystoreProps.getProperty(prop)?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("KEYSTORE_FILE", "storeFile")?.let { rootProject.file(it) }?.takeIf { it.exists() }
val hasReleaseKey = releaseStoreFile != null && signingValue("KEYSTORE_PASSWORD", "storePassword") != null

// Weboldal (adatvédelem + fióktörlés) – gradle.properties: bluetune.siteUrl
val siteUrl = (findProperty("bluetune.siteUrl") as String?)?.trimEnd('/') ?: "https://example.com/bluetune"

android {
    namespace = "hu.bluetune"
    compileSdk = 36

    defaultConfig {
        applicationId = "hu.bluetune"
        minSdk = 26
        targetSdk = 36
        // GitHub Actions alatt a futás sorszáma lesz a versionCode -> mindig növekszik (Play ezt megköveteli)
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()?.plus(100) ?: 1
        versionName = (findProperty("bluetune.versionName") as String?) ?: "1.1.0"

        buildConfigField("String", "PRIVACY_URL", "\"$siteUrl/privacy.html\"")
        buildConfigField("String", "DELETE_ACCOUNT_URL", "\"$siteUrl/delete-account.html\"")
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = signingValue("KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signingValue("KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                logger.warn("BlueTune: nincs saját kiadási kulcs – a release a DEBUG kulccsal lesz aláírva.")
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.04.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")

    implementation(platform("com.google.firebase:firebase-bom:33.13.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
}
