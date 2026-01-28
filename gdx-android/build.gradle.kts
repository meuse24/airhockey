import java.io.File

plugins {
    alias(libs.plugins.android.application)
}

val natives by configurations.creating

android {
    namespace = "info.meuse24.airhockey.gdx"
    compileSdk = 36

    defaultConfig {
        applicationId = "info.meuse24.airhockey.gdx"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    sourceSets {
        getByName("main") {
            jniLibs {
                srcDirs(File(layout.buildDirectory.get().asFile, "natives"))
            }
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.gdx.backend.android)
    implementation(libs.gdx.core)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")

    natives(libs.gdx.platform) { artifact { classifier = "natives-armeabi-v7a" } }
    natives(libs.gdx.platform) { artifact { classifier = "natives-arm64-v8a" } }
    natives(libs.gdx.platform) { artifact { classifier = "natives-x86" } }
    natives(libs.gdx.platform) { artifact { classifier = "natives-x86_64" } }
    natives(libs.gdx.box2d.platform) { artifact { classifier = "natives-armeabi-v7a" } }
    natives(libs.gdx.box2d.platform) { artifact { classifier = "natives-arm64-v8a" } }
    natives(libs.gdx.box2d.platform) { artifact { classifier = "natives-x86" } }
    natives(libs.gdx.box2d.platform) { artifact { classifier = "natives-x86_64" } }
}

val copyAndroidNatives by tasks.registering(Copy::class) {
    // Process each native JAR file individually
    for (file in natives.files) {
        // Extract ABI from JAR filename: gdx-platform-1.13.0-natives-armeabi-v7a.jar -> armeabi-v7a
        val fileName = file.name
        val abiMatch = Regex("""natives-([^.]+)\.jar""").find(fileName)
        if (abiMatch != null) {
            val abi = abiMatch.groupValues[1]
            from(zipTree(file)) {
                include("**/libgdx.so")
                include("**/libgdx-box2d.so")
                into(abi)
            }
        }
    }

    includeEmptyDirs = false
    into(layout.buildDirectory.dir("natives"))
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

tasks.named("preBuild") {
    dependsOn(copyAndroidNatives)
}
