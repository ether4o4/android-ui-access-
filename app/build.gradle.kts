plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ether4o4.odcm"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ether4o4.odcm"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += listOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "/META-INF/*.kotlin_module",
                "/META-INF/versions/**",
            )
        }
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
        checkReleaseBuilds = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.anthropic:anthropic-java:3.3.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
