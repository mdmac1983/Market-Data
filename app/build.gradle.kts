import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// API keys come from (in order): environment variables (GitHub Actions secrets) or an untracked keys.properties file.
val keyProps = Properties().apply {
    val f = rootProject.file("keys.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun key(name: String): String = (System.getenv(name) ?: keyProps.getProperty(name) ?: "").trim()

android {
    namespace = "app.orionmd.marketdata"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.orionmd.marketdata"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = "1.0"

        listOf(
            "FINNHUB_API_KEY", "TWELVEDATA_API_KEY", "COINSTATS_API_KEY",
            "FRED_API_KEY", "FMP_API_KEY", "ALPHAVANTAGE_API_KEY",
            "NEWSAPI_API_KEY", "MARKETAUX_API_KEY", "POLYGON_API_KEY",
        ).forEach { buildConfigField("String", it, "\"${key(it)}\"") }
    }

    signingConfigs {
        create("release") {
            val ks = key("KEYSTORE_FILE")
            if (ks.isNotEmpty() && file(ks).exists()) {
                storeFile = file(ks)
                storePassword = key("KEYSTORE_PASSWORD")
                keyAlias = key("KEY_ALIAS")
                keyPassword = key("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Use the release keystore when provided; otherwise fall back to the debug key so the APK still installs.
            val rel = signingConfigs.getByName("release")
            signingConfig = if (rel.storeFile != null) rel else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
        )
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

// Name the output Market_Data.apk
android.applicationVariants.all {
    outputs.all {
        (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
            if (buildType.name == "release") "Market_Data.apk" else "Market_Data-${buildType.name}.apk"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.01.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("sh.calvin.reorderable:reorderable:2.4.3")
    implementation("io.coil-kt:coil-compose:2.7.0")
}
