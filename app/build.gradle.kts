plugins {
    id("com.android.application")
}

// The signing key lives outside the repository: path and passwords go to ~/.gradle/gradle.properties
// (sofatv.storeFile, sofatv.storePassword, sofatv.keyAlias, sofatv.keyPassword).
// Without them the release build is signed with the debug key.
val releaseStoreFile = providers.gradleProperty("sofatv.storeFile").orNull

// Where the app looks for updates. A test build can point it at a local server serving the same
// JSON: ./gradlew assembleRelease -Psofatv.updateUrl=http://192.168.0.10:8000/latest.json
val updateUrl = providers.gradleProperty("sofatv.updateUrl").orNull
    ?: "https://api.github.com/repos/Maxic112/sofa-tv-launcher/releases/latest"

android {
    namespace = "dev.sofatv.launcher"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.sofatv.launcher"
        minSdk = 24
        targetSdk = 36
        versionCode = 47
        versionName = "0.15.0"
        resValue("string", "update_url", updateUrl)
        // Plain HTTP only for a local test server; GitHub is HTTPS.
        manifestPlaceholders["usesCleartextTraffic"] = updateUrl.startsWith("http:").toString()
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = providers.gradleProperty("sofatv.storePassword").get()
                keyAlias = providers.gradleProperty("sofatv.keyAlias").get()
                keyPassword = providers.gradleProperty("sofatv.keyPassword").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        resValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation("androidx.recyclerview:recyclerview:1.4.0")
}
