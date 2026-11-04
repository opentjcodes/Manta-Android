plugins {
    id("com.android.application") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "2.0.20"
}

val syncRootLogo by tasks.registering(Copy::class) {
    from(rootProject.file("logo.png"))
    into(layout.projectDirectory.dir("src/main/res/drawable-nodpi"))
    isIgnoreEmptyDirs = true
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(syncRootLogo)
}

android {
    namespace = "app.manta.browser"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.manta.browser"
        minSdk = 26
        targetSdk = 34
        versionCode = 200
        versionName = "2.0.0-manta"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
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
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "META-INF/**",
                "kotlin/**",
                "**.bin",
                "DebugProbesKt.bin"
            )
        }
    }
}

dependencies {
    // 100% pure Android SDK + Manta Rust Core (`libmanta_core.so`). Zero bloatware dependencies.
}
