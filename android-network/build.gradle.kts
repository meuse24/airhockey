plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "info.meuse24.airhockey.network"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
