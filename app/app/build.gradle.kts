import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// 固定签名：所有出包都用同一把 key，避免换机器后覆盖安装报「签名冲突」。
// 路径与口令放在 app/local.properties（已在 .gitignore，不会提交）或环境变量里：
//   COMII_KEYSTORE=/path/to/comii.jks
//   COMII_KEYSTORE_PASSWORD=...
//   COMII_KEY_ALIAS=comii
//   COMII_KEY_PASSWORD=...
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun signingValue(key: String, fallback: String? = null): String? =
    localProps.getProperty(key) ?: System.getenv(key) ?: fallback

val comiiStore = signingValue("COMII_KEYSTORE")

android {
    namespace = "com.comii.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.comii.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 49
        versionName = "3.0.2"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (comiiStore != null) {
            create("comii") {
                storeFile = file(comiiStore)
                storePassword = signingValue("COMII_KEYSTORE_PASSWORD")
                keyAlias = signingValue("COMII_KEY_ALIAS", "comii")
                keyPassword = signingValue("COMII_KEY_PASSWORD") ?: signingValue("COMII_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (comiiStore != null) signingConfig = signingConfigs.getByName("comii")
        }
        debug {
            // 本机调试包也走同一把 key，升级安装不会互相冲突
            if (comiiStore != null) signingConfig = signingConfigs.getByName("comii")
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.navigation:navigation-compose:2.8.3")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-video:2.7.0")

    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}





































