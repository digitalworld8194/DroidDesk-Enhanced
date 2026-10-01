import java.util.Properties

plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

// Preview builds (com.orailnoor.droiddesk.preview) are the daily-driver
// DroidDesk: they install side by side with the original app under their own
// applicationId (own data dir, own abstract sockets), are labelled
// "DroidDesk" and declare HOME like the original. Enable with
// DROIDDESK_PREVIEW=1 or -Pdroiddesk.preview=true.
val originalApplicationId = "com.orailnoor.droiddesk"
val isPreviewBuild = (project.findProperty("droiddesk.preview") ?: System.getenv("DROIDDESK_PREVIEW"))
    ?.toString()?.lowercase() in setOf("1", "true", "yes")
val droiddeskApplicationId = if (isPreviewBuild) "$originalApplicationId.preview" else originalApplicationId

// Permanent release key, kept outside the repository (see SIGNING.md). When the
// properties file is absent (e.g. CI) release builds fall back to the debug key
// and must be re-signed with scripts/sign-and-install.sh before installing.
val releaseKeystoreProperties = Properties().apply {
    val path = System.getenv("DROIDDESK_KEYSTORE_PROPERTIES")
        ?: "${System.getProperty("user.home")}/.droiddesk-signing/keystore.properties"
    val file = File(path)
    if (file.isFile) file.inputStream().use { load(it) }
}

android {
    namespace = "com.orailnoor.droiddesk"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    defaultConfig {
        applicationId = droiddeskApplicationId
        minSdk = 28  // Downgraded to 28 to bypass W^X (Write XOR Execute) restrictions on app data
        targetSdk = 28 // API 28 completely disables the Android 10+ execve() block
        versionCode = flutter.versionCode
        versionName = flutter.versionName
        if (isPreviewBuild) versionNameSuffix = "-preview"
        manifestPlaceholders["appLabel"] = "DroidDesk"

        ndk {
            // ARM64 only — all modern Android phones
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        if (!releaseKeystoreProperties.isEmpty) {
            create("permanent") {
                storeFile = file(releaseKeystoreProperties.getProperty("storeFile"))
                storePassword = releaseKeystoreProperties.getProperty("storePassword")
                keyAlias = releaseKeystoreProperties.getProperty("keyAlias")
                keyPassword = releaseKeystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // GitHub-distributed testing builds intentionally use Android's
            // debug key so release APKs are directly installable; local builds
            // with the permanent key available sign with it instead.
            signingConfig = signingConfigs.findByName("permanent") ?: signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Enable native (C/C++) build support for wlroots integration
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    if (isPreviewBuild) {
        // The prebuilt jniLibs/libsocket_hook.so has the regular app's prefix
        // compiled in; build a copy for this applicationId's data directory.
        defaultConfig.externalNativeBuild.cmake.arguments.add(
            "-DDROIDDESK_HOOK_PREFIX=/data/user/0/$droiddeskApplicationId/files/usr",
        )
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        jniLibs.useLegacyPackaging = true
    }
}

flutter {
    source = "../.."
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
