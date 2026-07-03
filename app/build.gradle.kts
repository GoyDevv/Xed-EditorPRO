import java.util.Properties

plugins {
    alias(libs.plugins.android.baselineprofile)
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ktfmt)
}

android {
    namespace = "com.rk.application"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.rk.xededitor"
        minSdk = 26

        targetSdk = 37

        // versioning
        versionCode = 128
        versionName = "4.2.1"
        vectorDrawables { useSupportLibrary = true }
    }

    buildFeatures {
        viewBinding = true
        compose = true
        buildConfig = true
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging { jniLibs { useLegacyPackaging = true } }

    signingConfigs {
        create("release") {
            val isGitHubActions = System.getenv("GITHUB_ACTIONS") == "true"

            val propertiesFilePath =
                if (isGitHubActions) {
                    "/tmp/signing.properties"
                } else {
                    "/home/rohit/Android/xed-signing/signing.properties"
                }

            val propertiesFile = File(propertiesFilePath)
            var configured = false
            if (propertiesFile.exists()) {
                val properties = Properties()
                properties.load(propertiesFile.inputStream())
                val pKeyAlias = properties["keyAlias"] as String?
                val pKeyPassword = properties["keyPassword"] as String?
                val pStorePassword = properties["storePassword"] as String?
                val pStoreFile =
                    if (isGitHubActions) {
                        File("/tmp/xed.keystore")
                    } else {
                        (properties["storeFile"] as String?)?.let { File(it) }
                    }

                // Only use real signing when every value is actually present (forks without
                // signing secrets get an empty properties file, which must not be used).
                if (
                    !pKeyAlias.isNullOrBlank() &&
                        !pKeyPassword.isNullOrBlank() &&
                        !pStorePassword.isNullOrBlank() &&
                        pStoreFile != null &&
                        pStoreFile.exists()
                ) {
                    keyAlias = pKeyAlias
                    keyPassword = pKeyPassword
                    storeFile = pStoreFile
                    storePassword = pStorePassword
                    configured = true
                }
            }

            if (!configured) {
                // Fallback so `assembleRelease` produces an installable APK on forks/CI without
                // signing secrets. NOTE: signed with the public testkey — not for store upload.
                println("Release signing secrets not found; falling back to bundled debug testkey.")
                storeFile = file(layout.buildDirectory.dir("../testkey.keystore"))
                storePassword = "testkey"
                keyAlias = "testkey"
                keyPassword = "testkey"
            }
        }
        getByName("debug") {
            storeFile = file(layout.buildDirectory.dir("../testkey.keystore"))
            storePassword = "testkey"
            keyAlias = "testkey"
            keyPassword = "testkey"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            isCrunchPngs = false

            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

            signingConfig = signingConfigs.getByName("release")
        }

        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-DEBUG"
            resValue("string", "app_name", "Xed-Debug")
        }

        create("benchmark") {
            initWith(buildTypes.getByName("release"))
            matchingFallbacks += listOf("release")
            isDebuggable = false
        }

        // XED-PRO release build: distinct applicationId + label so it installs ALONGSIDE the
        // official Xed-Editor (and the debug build) instead of replacing it. This is a real release
        // build (initWith release, not debuggable). It is ALWAYS signed with the bundled, committed
        // keystore (the "debug" testkey), so every build — locally, on any fork, and on CI — uses the
        // exact same signing key. That means each new XED-PRO APK installs as an update over the
        // previous one (Android requires an identical signature to update). Do NOT change this key,
        // or existing installs will fail to update until they're uninstalled.
        create("pro") {
            initWith(buildTypes.getByName("release"))
            matchingFallbacks += listOf("release")
            applicationIdSuffix = ".pro"
            versionNameSuffix = "-PRO"
            resValue("string", "app_name", "XED-PRO")
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = false
        }
    }
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(libs.androidx.profileinstaller)
    coreLibraryDesugaring(libs.desugar)

    baselineProfile(project(":baselineprofile"))
    implementation(project(":core:main"))
}
