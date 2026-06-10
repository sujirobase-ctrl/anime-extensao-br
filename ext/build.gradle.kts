import org.jmailen.gradle.kotlinter.tasks.LintTask
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jmailen.kotlinter")
}

// Extension metadata — versionCode is bumped here on each release.
val extName = "Dattebayo BR"
val extClass = ".DattebayoBR"
val extVersionCode = 18
val extIsNsfw = false

android {
    compileSdk = AndroidConfig.compileSdk
    namespace = "eu.kanade.tachiyomi.animeextension.pt.dattebayobr"

    defaultConfig {
        applicationId = "eu.kanade.tachiyomi.animeextension.pt.dattebayobr"
        minSdk = AndroidConfig.minSdk
        targetSdk = AndroidConfig.targetSdk

        versionCode = extVersionCode
        versionName = "14.$extVersionCode"
        base.archivesName.set("aniyomi-pt.dattebayobr-v$versionName")

        manifestPlaceholders.putAll(
            mapOf(
                "appName" to "Aniyomi: $extName",
                "extClass" to extClass,
                "nsfw" to if (extIsNsfw) 1 else 0,
            ),
        )
    }

    sourceSets {
        named("main") {
            manifest.srcFile("AndroidManifest.xml")
            java.srcDirs("src/main/kotlin")
            res.setSrcDirs(listOf("res"))
        }
    }

    // Signing config is fed from environment variables (CI) or a local
    // keystore.properties file (local builds). Releases on tag use CI-provided
    // secrets; pushes to main produce an unsigned artifact for sanity-check only.
    val keystorePropsFile = rootProject.file("keystore.properties")
    val keystoreProps = Properties().apply {
        if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use(::load)
    }
    val ciStoreFile = System.getenv("SIGNING_KEY_PATH")
    val ciStorePass = System.getenv("SIGNING_KEY_STORE_PASSWORD")
    val ciKeyAlias = System.getenv("SIGNING_KEY_ALIAS")
    val ciKeyPass = System.getenv("SIGNING_KEY_PASSWORD")
    val hasSigning = (
        !ciStoreFile.isNullOrBlank() &&
            !ciStorePass.isNullOrBlank() &&
            !ciKeyAlias.isNullOrBlank() &&
            !ciKeyPass.isNullOrBlank()
        ) || keystoreProps.getProperty("storeFile") != null

    if (hasSigning) {
        signingConfigs {
            create("release") {
                storeFile = file(
                    ciStoreFile ?: keystoreProps.getProperty("storeFile"),
                )
                storePassword = ciStorePass ?: keystoreProps.getProperty("storePassword")
                keyAlias = ciKeyAlias ?: keystoreProps.getProperty("keyAlias")
                keyPassword = ciKeyPass ?: keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    dependenciesInfo {
        includeInApk = false
    }

    buildFeatures {
        aidl = false
        renderScript = false
        resValues = false
        shaders = false
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_1_8.toString()
        freeCompilerArgs += "-opt-in=kotlinx.serialization.ExperimentalSerializationApi"
    }
}

dependencies {
    compileOnly(libs.bundles.common)
}

tasks.withType<LintTask>().configureEach {
    // The source folder is named `src/main/kotlin`, not the default `src/main/java`,
    // so we explicitly tell kotlinter where to find files.
    source(files("src/main/kotlin"))
}
