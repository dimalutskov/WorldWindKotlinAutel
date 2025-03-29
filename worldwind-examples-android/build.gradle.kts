plugins {
    kotlin("android")
    id("com.android.application")
}

android {
    namespace = "${project.group}.examples"
    compileSdk = extra["targetSdk"] as Int

    defaultConfig {
        applicationId = namespace
        minSdk = extra["minSdk"] as Int
        targetSdk = extra["targetSdk"] as Int
        versionCode = extra["versionCode"] as Int
        versionName = version as String
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            setProguardFiles(listOf(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro"))
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }
}

dependencies {
    implementation(project(":worldwind"))
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.material)
    implementation(libs.mil.sym.android)

    implementation(files("libs/autel-sdk-release.aar"))

    implementation("com.google.protobuf:protobuf-java:3.25.1")
    implementation("com.google.code.gson:gson:2.12.1")

    coreLibraryDesugaring(libs.desugar)
}