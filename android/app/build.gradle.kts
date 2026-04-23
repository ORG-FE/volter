plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}


fun volterVersionName(): String =
    System.getenv("VOLTER_VERSION_NAME") ?: System.getenv("PTERA_VERSION_NAME")
        ?: (project.findProperty("volter.versionName") as String?)
        ?: (project.findProperty("ptera.versionName") as String?)
        ?: "0.0.0-dev"

fun volterVersionCode(): Int =
    System.getenv("VOLTER_VERSION_CODE")?.toIntOrNull()
        ?: System.getenv("PTERA_VERSION_CODE")?.toIntOrNull()
        ?: (project.findProperty("volter.versionCode") as String?)?.toIntOrNull()
        ?: (project.findProperty("ptera.versionCode") as String?)?.toIntOrNull()
        ?: 1

val ciDebugKeystore = file("ci-debug.keystore")
val ciKeystorePass =
    System.getenv("VOLTER_CI_KEYSTORE_PASS") ?: System.getenv("PTERA_CI_KEYSTORE_PASS")
        ?: (project.findProperty("volter.ciKeystorePass") as String?)
        ?: (project.findProperty("ptera.ciKeystorePass") as String?)
        ?: "volterci-debug"

android {
    namespace = "dev.c0redev.volter"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.c0redev.volter"
        minSdk = 33
        targetSdk = 34

        versionCode = volterVersionCode()
        versionName = volterVersionName()
    }

    signingConfigs {
        if (ciDebugKeystore.exists()) {
            create("ciDebug") {
                storeFile = ciDebugKeystore
                storePassword = ciKeystorePass
                keyAlias = "pteraci"
                keyPassword = ciKeystorePass
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            if (ciDebugKeystore.exists()) {
                signingConfig = signingConfigs.getByName("ciDebug")
            }
        }
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
            )
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation(files("libs/volter-core.aar"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.core:core-splashscreen:1.0.1")

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}

